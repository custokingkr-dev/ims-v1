package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.tracker.TrackerView;
import com.custoking.ims.operationsservice.infrastructure.StaffDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The names the tracker shows for step assignees and note authors. Identity owns them; this keeps each answer, per user
 * id, for {@link #TTL} in a cache of at most {@link #MAX_ENTRIES}, so a busy screen asks identity rarely. An id identity
 * leaves out (not staff) is remembered as unknown for as long.
 *
 * <p><b>Never in a transaction.</b> Identity is asked only with no transaction open on the calling thread, so no pooled
 * connection or tracker row lock is held while it answers (the pattern of {@code TrackerStatusOutbox.attempt}). Asked
 * from inside one, the lookup serves what is cached and leaves the rest unknown.
 *
 * <p><b>Optional.</b> Names are a convenience: when identity cannot be asked they stay null, the tracker still answers,
 * and the failure is not cached, so the next view asks again.
 */
@Component
public class StaffNames {
    private static final Logger LOG = LoggerFactory.getLogger(StaffNames.class);
    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ENTRIES = 1_000;

    private final StaffDirectory directory;
    private final Clock clock;
    /** Insertion-ordered and re-inserted on refresh, so the eldest entry is always the first to expire. Guarded by itself. */
    private final LinkedHashMap<Long, Known> cache = new LinkedHashMap<>();

    /** {@code name} is null for an id identity left out. */
    private record Known(String name, Instant expiresAt) { }

    public StaffNames(StaffDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    /** The view with every assignee's and note author's name that is known; the rest stay null. */
    public TrackerView named(TrackerView view) {
        if (view == null) return null;
        Set<Long> ids = new LinkedHashSet<>();
        for (TrackerView.Step step : view.steps()) if (step.assigneeUserId() != null) ids.add(step.assigneeUserId());
        for (TrackerView.Note note : view.internalNotes()) ids.add(note.authorUserId());
        return ids.isEmpty() ? view : view.withStaffNames(lookup(ids));
    }

    /** The known names of these users. Identity is asked, in batches of at most 200, only about ids not cached. */
    public Map<Long, String> lookup(Collection<Long> userIds) {
        Map<Long, String> found = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return found;
        List<Long> missing = new ArrayList<>();
        synchronized (cache) {
            Instant now = clock.instant();
            for (Long id : new LinkedHashSet<>(userIds)) {
                if (id == null) continue;
                Known known = cache.get(id);
                if (known == null || !now.isBefore(known.expiresAt())) missing.add(id);
                else if (known.name() != null) found.put(id, known.name());
            }
        }
        if (missing.isEmpty()) return found;
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            LOG.warn("Names of {} staff left unknown: identity is never asked while a transaction holds a connection", missing.size());
            return found;
        }
        for (int from = 0; from < missing.size(); from += StaffDirectory.MAX_IDS) {
            List<Long> batch = missing.subList(from, Math.min(from + StaffDirectory.MAX_IDS, missing.size()));
            Map<Long, String> answered;
            try {
                answered = directory.fullNames(batch);
            } catch (RuntimeException unavailable) {
                // Not cached: the next view asks again. The other batches would meet the same outage.
                LOG.warn("Staff names left unknown: {}", unavailable.toString());
                return found;
            }
            synchronized (cache) {
                Instant expiresAt = clock.instant().plus(TTL);
                for (Long id : batch) {
                    String name = answered == null ? null : answered.get(id);
                    cache.remove(id);
                    cache.put(id, new Known(name, expiresAt));
                    if (name != null) found.put(id, name);
                }
                evict();
            }
        }
        return found;
    }

    /** Drops expired entries, then the eldest until the cache is within its bound. Called holding the cache's lock. */
    private void evict() {
        Instant now = clock.instant();
        Iterator<Known> eldest = cache.values().iterator();
        while (eldest.hasNext()) {
            Known known = eldest.next();
            if (cache.size() <= MAX_ENTRIES && now.isBefore(known.expiresAt())) break;
            eldest.remove();
        }
    }

    int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
