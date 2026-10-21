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
 * from inside one, the lookup serves what is cached, expired names included, and leaves the rest unknown.
 *
 * <p><b>Optional.</b> Names are a convenience: when identity cannot be asked the tracker still answers. A name already
 * known is kept past its ten minutes and served until a refresh succeeds; a name never known stays null. After a
 * failure identity is left alone for {@link #BACKOFF}, so a lasting outage or misconfiguration costs one call and one
 * warning a minute, not one per view.
 */
@Component
public class StaffNames {
    private static final Logger LOG = LoggerFactory.getLogger(StaffNames.class);
    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ENTRIES = 1_000;
    /** How long identity is left alone after a names call failed. */
    static final Duration BACKOFF = Duration.ofSeconds(60);

    private final StaffDirectory directory;
    private final Clock clock;
    /**
     * Insertion-ordered and re-inserted on refresh, so the eldest entry is the least recently refreshed. An expired entry
     * stays (bounded by {@link #MAX_ENTRIES}) as the fallback for a failed refresh. Guarded by itself.
     */
    private final LinkedHashMap<Long, Known> cache = new LinkedHashMap<>();
    /** Identity is not asked before this instant: the back-off after a failed call. Guarded by {@link #cache}. */
    private Instant askAgainAt = Instant.MIN;

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

    /**
     * The known names of these users. Identity is asked, in batches of at most 200, only about ids whose cached answer is
     * missing or older than {@link #TTL}; whenever it is not asked, or cannot answer, an expired name is served instead.
     */
    public Map<Long, String> lookup(Collection<Long> userIds) {
        Map<Long, String> found = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) return found;
        List<Long> missing = new ArrayList<>();
        Map<Long, String> expired = new HashMap<>();
        boolean backingOff;
        synchronized (cache) {
            Instant now = clock.instant();
            for (Long id : new LinkedHashSet<>(userIds)) {
                if (id == null) continue;
                Known known = cache.get(id);
                if (known != null && now.isBefore(known.expiresAt())) {
                    if (known.name() != null) found.put(id, known.name());
                    continue;
                }
                missing.add(id);
                if (known != null && known.name() != null) expired.put(id, known.name());
            }
            backingOff = now.isBefore(askAgainAt);
        }
        if (missing.isEmpty()) return found;
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            LOG.warn("Names of {} staff not refreshed: identity is never asked while a transaction holds a connection", missing.size());
            found.putAll(expired);
            return found;
        }
        if (backingOff) {
            // Identity failed less than a minute ago; it was warned about then.
            found.putAll(expired);
            return found;
        }
        for (int from = 0; from < missing.size(); from += StaffDirectory.MAX_IDS) {
            List<Long> batch = missing.subList(from, Math.min(from + StaffDirectory.MAX_IDS, missing.size()));
            Map<Long, String> answered;
            try {
                answered = directory.fullNames(batch);
            } catch (RuntimeException unavailable) {
                // The other batches would meet the same outage: back off, and keep serving the names already known.
                synchronized (cache) {
                    askAgainAt = clock.instant().plus(BACKOFF);
                }
                LOG.warn("Staff names not refreshed; identity is left alone for {} s: {}", BACKOFF.toSeconds(), unavailable.toString());
                for (Long id : missing.subList(from, missing.size())) {
                    if (expired.containsKey(id)) found.put(id, expired.get(id));
                }
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

    /**
     * Drops the least recently refreshed entries until the cache is within its bound. Expired entries are kept otherwise:
     * they are the fallback when a refresh fails. Called holding the cache's lock.
     */
    private void evict() {
        Iterator<Known> eldest = cache.values().iterator();
        while (cache.size() > MAX_ENTRIES && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }

    int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
