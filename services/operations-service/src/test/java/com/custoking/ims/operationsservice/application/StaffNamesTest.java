package com.custoking.ims.operationsservice.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.custoking.ims.operationsservice.infrastructure.StaffDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** The tracker's staff-name cache: ten minutes per id, a bounded size, batches of 200, and never a peer call in a transaction. */
class StaffNamesTest {
    private final TestClock clock = new TestClock();
    private final Map<Long, String> staff = new HashMap<>();
    private final List<List<Long>> calls = new CopyOnWriteArrayList<>();
    private volatile boolean down;
    private final StaffDirectory directory = ids -> {
        calls.add(List.copyOf(ids));
        if (ids.size() > StaffDirectory.MAX_IDS) throw new IllegalArgumentException("too many ids in one call");
        if (down) throw new IllegalStateException("identity is down");
        Map<Long, String> found = new HashMap<>();
        for (Long id : ids) if (staff.containsKey(id)) found.put(id, staff.get(id));
        return found;
    };
    private final StaffNames names = new StaffNames(directory, clock);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void eachNameIsCachedForTenMinutes() {
        staff.put(7L, "Asha Operator");
        assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L)));
        clock.advance(Duration.ofMinutes(9));
        staff.put(7L, "Asha Renamed");
        assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L)), "still within ten minutes");
        assertEquals(1, calls.size());
        clock.advance(Duration.ofMinutes(2));
        assertEquals(Map.of(7L, "Asha Renamed"), names.lookup(List.of(7L)), "asked again after ten minutes");
        assertEquals(2, calls.size());
    }

    @Test
    void onlyTheIdsNotCachedAreAskedForInBatchesOfAtMost200() {
        LongStream.rangeClosed(1, 450).forEach(id -> staff.put(id, "Staff " + id));
        names.lookup(List.of(1L, 2L));
        calls.clear();
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 450).boxed().toList());
        ids.add(1L);
        ids.add(null);
        Map<Long, String> found = names.lookup(ids);
        assertEquals(450, found.size(), "every id has a name, the two cached ones from the cache");
        assertEquals("Staff 450", found.get(450L));
        assertTrue(calls.stream().allMatch(call -> call.size() <= 200), calls.stream().map(List::size).toList().toString());
        assertEquals(448, calls.stream().mapToInt(List::size).sum(), "each id not cached is asked for once");
        assertTrue(calls.stream().flatMap(List::stream).noneMatch(id -> id == 1L || id == 2L));
        assertEquals(3, calls.size());
    }

    @Test
    void anIdIdentityLeavesOutIsRememberedAsUnknown() {
        staff.put(7L, "Asha Operator");
        assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L, 99L)));
        assertEquals(Map.of(), names.lookup(List.of(99L)), "a school admin or former operator has no name here");
        assertEquals(1, calls.size(), "and is not asked about again within ten minutes");
    }

    @Test
    void afterAFailureIdentityIsLeftAloneForAMinute() {
        staff.put(7L, "Asha Operator");
        down = true;
        ListAppender<ILoggingEvent> logs = capture();
        try {
            assertEquals(Map.of(), names.lookup(List.of(7L)));
            down = false;
            clock.advance(Duration.ofSeconds(30));
            assertEquals(Map.of(), names.lookup(List.of(7L)), "not asked again within the minute");
            assertEquals(Map.of(), names.lookup(List.of(8L)), "not for another id either");
            assertEquals(1, calls.size());
            clock.advance(Duration.ofSeconds(31));
            assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L)), "the failure itself is not cached");
            assertEquals(2, calls.size());
        } finally {
            release(logs);
        }
        assertEquals(1, logs.list.stream().filter(event -> event.getLevel() == Level.WARN).count(),
                "one warning for the outage, not one per lookup: " + logs.list);
    }

    @Test
    void anExpiredNameIsServedWhileItsRefreshFails() {
        staff.put(7L, "Asha Operator");
        names.lookup(List.of(7L));
        clock.advance(Duration.ofMinutes(11));
        down = true;
        assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L, 8L)), "the expired name rather than none; 8 was never known");
        assertEquals(2, calls.size(), "the refresh was tried");
        clock.advance(Duration.ofSeconds(30));
        assertEquals(Map.of(7L, "Asha Operator"), names.lookup(List.of(7L)), "and served through the back-off");
        assertEquals(2, calls.size());

        down = false;
        staff.put(7L, "Asha Renamed");
        clock.advance(Duration.ofSeconds(31));
        assertEquals(Map.of(7L, "Asha Renamed"), names.lookup(List.of(7L)), "replaced once a refresh succeeds");
        staff.remove(7L);
        clock.advance(Duration.ofMinutes(11));
        assertEquals(Map.of(), names.lookup(List.of(7L)), "a user identity no longer calls staff loses the name");
    }

    @Test
    void theCacheIsBounded() {
        LongStream.rangeClosed(1, 2_500).forEach(id -> staff.put(id, "Staff " + id));
        for (long start = 1; start <= 2_500; start += 100) {
            names.lookup(LongStream.range(start, start + 100).boxed().toList());
        }
        assertTrue(names.size() <= StaffNames.MAX_ENTRIES, "size " + names.size());
        calls.clear();
        assertEquals("Staff 2500", names.lookup(List.of(2_500L)).get(2_500L));
        assertTrue(calls.isEmpty(), "the newest entries are kept");
    }

    @Test
    void noPeerCallIsMadeWhileATransactionHoldsAConnection() {
        staff.put(7L, "Asha Operator");
        names.lookup(List.of(7L));
        calls.clear();
        staff.put(8L, "Ravi Operator");
        TransactionSynchronizationManager.initSynchronization();
        Map<Long, String> inside = names.lookup(List.of(7L, 8L));
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals(Map.of(7L, "Asha Operator"), inside, "a cached name is still served; the unknown one stays unknown");
        assertTrue(calls.isEmpty(), "identity is not asked from inside a transaction");
        assertEquals(Map.of(7L, "Asha Operator", 8L, "Ravi Operator"), names.lookup(List.of(7L, 8L)), "and is asked once outside it");

        clock.advance(Duration.ofMinutes(11));
        calls.clear();
        TransactionSynchronizationManager.initSynchronization();
        Map<Long, String> expiredInside = names.lookup(List.of(7L));
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals(Map.of(7L, "Asha Operator"), expiredInside, "an expired name is served rather than refreshed inside it");
        assertTrue(calls.isEmpty());
    }

    @Test
    void noIdsAskNothing() {
        assertEquals(Map.of(), names.lookup(List.of()));
        assertEquals(Map.of(), names.lookup(java.util.Arrays.asList((Long) null)));
        assertTrue(calls.isEmpty());
    }

    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StaffNames.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StaffNames.class)).detachAppender(appender);
    }

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T06:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
