package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplates;
import com.custoking.ims.operationsservice.persistence.ActivityRepository;
import com.custoking.ims.operationsservice.persistence.ActivityRepository.RawEntry;
import com.custoking.ims.operationsservice.persistence.WorkflowRequestScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An order's activity history (design D40, §4.3 "Order activity history"): "calls, changes and approvals must be logged
 * standardly" [SOP §2]. It merges four sources into one newest-first timeline: the workflow actions (stage moves,
 * approvals, saves, vendor, designer, contact and group changes), the stage and internal notes, the call log, and the
 * legacy notebook follow-ups. The browser adds design-file uploads and removals from school-core's asset list.
 * <ul>
 *   <li><b>Grouping.</b> Consecutive data saves by one person on one stage within ten minutes are one entry with a
 *       {@code count}: a checklist autosaves on every tick.</li>
 *   <li><b>Paging.</b> Pages are cut at whole timestamps; {@code nextBefore} is the oldest time on the page, and the next
 *       page holds the entries strictly older than it.</li>
 * </ul>
 * Anyone who may see the order may read its history.
 */
@Service
public class ActivityService {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    /** Rows read from the database at a time; a page of grouped saves can need several batches. */
    static final int BATCH = 200;
    static final Duration GROUP_WINDOW = Duration.ofMinutes(10);
    /** The action a stage-data save records; only these are grouped. */
    static final String STEP_DATA_SAVED = "STEP_DATA_SAVED";
    /** The stage the legacy notebook follow-ups were logged on (they carry no stage of their own). */
    static final String FOLLOW_UP_STEP_CODE = "PRODUCTION_FOLLOW_UP";
    /** Keys of an action's JSON notes that hold its human text (a reason or comment), in order of preference. */
    private static final List<String> TEXT_KEYS = List.of("reason", "comment", "note");
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    /** The source of an entry. */
    public enum Kind { ACTION, NOTE, CALL, FOLLOW_UP }

    /**
     * One timeline entry.
     *
     * @param id      unique across kinds: {@code action:12}, {@code note:5}, {@code call:3}, {@code follow-up:2}; for a group,
     *                its newest member's
     * @param action  the {@code workflow_actions} code for an ACTION, otherwise null
     * @param at      when it happened; for a group, its newest member's time
     * @param text    a note's body, a call's notes, or an action's reason or comment
     * @param details kind-specific facts (changed field names and checklist counts; the call's contacted, outcome and
     *                follow-up; a follow-up's percentage and date; a note's type)
     * @param count   how many saves a group holds; 1 for everything else
     */
    public record ActivityEntry(String id, Kind kind, String action, Integer stepOrder, Long actorUserId, String actorName,
                                OffsetDateTime at, String text, Map<String, Object> details, int count) {
        ActivityEntry withActorName(String name) {
            return new ActivityEntry(id, kind, action, stepOrder, actorUserId, name, at, text, details, count);
        }
    }

    /**
     * One page of the timeline, newest first.
     *
     * @param nextBefore pass as {@code before} to read the older entries; null when there are none
     */
    public record ActivityPage(List<ActivityEntry> entries, OffsetDateTime nextBefore) {
        public ActivityPage {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final ActivityRepository repo;
    private final OrderAccess access;
    private final WorkflowRequestScope scope;
    private final TransactionTemplate tx;
    private final WorkflowTemplates templates;
    private final StaffNames names;
    private final ObjectMapper json;

    public ActivityService(ActivityRepository repo, @Lazy OrderAccess access, WorkflowRequestScope scope, TransactionTemplate tx,
                           WorkflowTemplates templates, StaffNames names, ObjectMapper json) {
        this.repo = repo;
        this.access = access;
        this.scope = scope;
        this.tx = tx;
        this.templates = templates;
        this.names = names;
        this.json = json;
    }

    /**
     * A page of the order's history.
     *
     * @param before only entries strictly older than this (the previous page's {@code nextBefore}); null for the newest
     * @param limit  entries per page, 1 to 200 (default 50); a page runs over by the entries sharing its oldest time
     * @param step   only this stage's entries (order-level internal notes are then left out); null for every stage
     */
    public ActivityPage activity(String orderId, OffsetDateTime before, Integer limit, Integer step) {
        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1 || max > MAX_LIMIT) throw bad("limit must be between 1 and " + MAX_LIMIT);
        if (step != null && (step < 1 || step > 99)) throw bad("step must be a stage number from 1 to 99");
        ActivityPage page = tx.execute(status -> {
            scope.applyOperatorScope();
            AccessibleOrder order = access.require(orderId, false);
            Integer followUpStep = templates.find(order.definitionId())
                    .flatMap(template -> template.stepByCode(FOLLOW_UP_STEP_CODE)).map(StepSpec::order).orElse(null);
            return page(cursor -> repo.batch(order.instanceId(), cursor, step, followUpStep, BATCH), before, max, BATCH, this::entry);
        });
        Set<Long> actors = page.entries().stream().map(ActivityEntry::actorUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (actors.isEmpty()) return page;
        Map<Long, String> known = names.lookup(actors);
        return new ActivityPage(page.entries().stream()
                .map(entry -> entry.actorUserId() == null ? entry : entry.withActorName(known.get(entry.actorUserId())))
                .toList(), page.nextBefore());
    }

    // ---- the timeline (pure) -----------------------------------------------------------------------------------------

    /** Reads the rows strictly older than a time (all rows for null), newest first, whole timestamps only. */
    @FunctionalInterface
    interface Batches {
        List<RawEntry> olderThan(OffsetDateTime cursor);
    }

    /** Turns one group of rows (one row, or several grouped saves, newest first) into an entry. */
    @FunctionalInterface
    interface EntryFactory {
        ActivityEntry entry(List<RawEntry> members);
    }

    /**
     * Groups the rows into entries and cuts a page of {@code limit} of them, reading more batches only as needed. A group is
     * final once a row that does not join it follows, or the rows run out; the page is extended past {@code limit} while
     * the next entry shares the page's oldest time, so that {@code nextBefore} (strictly older) skips nothing.
     */
    static ActivityPage page(Batches source, OffsetDateTime before, int limit, int batchSize, EntryFactory factory) {
        List<RawEntry> rows = new ArrayList<>();
        OffsetDateTime cursor = before;
        boolean exhausted = false;
        while (true) {
            List<List<RawEntry>> groups = group(rows);
            Cut cut = cut(groups, limit, exhausted);
            if (cut != null) {
                List<ActivityEntry> entries = groups.subList(0, cut.size()).stream().map(factory::entry).toList();
                return new ActivityPage(entries, cut.nextBefore());
            }
            List<RawEntry> batch = source.olderThan(cursor);
            if (batch.size() < batchSize) exhausted = true;
            if (batch.isEmpty()) continue;
            OffsetDateTime oldest = batch.get(batch.size() - 1).at();
            if (cursor != null && !oldest.isBefore(cursor)) throw new IllegalStateException("Activity rows must be older than the cursor");
            rows.addAll(batch);
            cursor = oldest;
        }
    }

    private record Cut(int size, OffsetDateTime nextBefore) { }

    /** Where the page ends, or null when more rows must be read to know. */
    private static Cut cut(List<List<RawEntry>> groups, int limit, boolean exhausted) {
        int finalGroups = exhausted ? groups.size() : Math.max(0, groups.size() - 1);
        if (!exhausted && finalGroups <= limit) return null;
        int size = Math.min(limit, finalGroups);
        if (size == 0) return new Cut(0, null);
        OffsetDateTime oldest = oldest(groups.get(size - 1));
        while (size < finalGroups && !newest(groups.get(size)).isBefore(oldest)) {
            OffsetDateTime groupOldest = oldest(groups.get(size));
            if (groupOldest.isBefore(oldest)) oldest = groupOldest;
            size++;
        }
        // The last group is still open: it may share the page's oldest time, so it cannot be ruled out yet.
        if (!exhausted && size == finalGroups) return null;
        return new Cut(size, size < groups.size() ? oldest : null);
    }

    /** Consecutive saves by one person on one stage within {@link #GROUP_WINDOW} of the group's newest save join it. */
    static List<List<RawEntry>> group(List<RawEntry> rows) {
        List<List<RawEntry>> groups = new ArrayList<>();
        List<RawEntry> current = null;
        for (RawEntry row : rows) {
            if (current != null && joins(current.get(0), row)) {
                current.add(row);
            } else {
                current = new ArrayList<>();
                current.add(row);
                groups.add(current);
            }
        }
        return groups;
    }

    private static boolean joins(RawEntry newest, RawEntry row) {
        return isSave(newest) && isSave(row) && newest.actorId() != null && newest.actorId().equals(row.actorId())
                && Objects.equals(newest.stepOrder(), row.stepOrder())
                && Duration.between(row.at(), newest.at()).compareTo(GROUP_WINDOW) <= 0;
    }

    private static boolean isSave(RawEntry row) {
        return ActivityRepository.ACTION.equals(row.kind()) && STEP_DATA_SAVED.equals(row.action());
    }

    private static OffsetDateTime newest(List<RawEntry> group) { return group.get(0).at(); }

    private static OffsetDateTime oldest(List<RawEntry> group) { return group.get(group.size() - 1).at(); }

    // ---- entries -----------------------------------------------------------------------------------------------------

    ActivityEntry entry(List<RawEntry> members) {
        RawEntry head = members.get(0);
        Integer step = head.stepOrder() == null || head.stepOrder() < 1 ? null : head.stepOrder();
        return switch (head.kind()) {
            case ActivityRepository.ACTION -> action(members, head, step);
            case ActivityRepository.NOTE -> new ActivityEntry("note:" + head.id(), Kind.NOTE, null, step, head.actorId(), null,
                    head.at(), head.text(), Map.of("type", step == null ? "INTERNAL" : "STAGE"), 1);
            case ActivityRepository.CALL -> new ActivityEntry("call:" + head.id(), Kind.CALL, null, step, head.actorId(), null,
                    head.at(), head.text(), callDetails(head), 1);
            case ActivityRepository.FOLLOW_UP -> new ActivityEntry("follow-up:" + head.id(), Kind.FOLLOW_UP, null, step,
                    head.actorId(), null, head.at(), null, followUpDetails(head), 1);
            default -> throw new IllegalStateException("Unknown activity source " + head.kind());
        };
    }

    /**
     * An action, or a group of saves. An action's notes are either plain text (a reason or comment, shown as the entry's
     * text) or a JSON object of what changed (the entry's details; its {@code reason}, {@code comment} or {@code note} is
     * the text). A group's details are merged newest first: list values (such as changed field names) are united, and
     * every other value is the newest save's.
     */
    private ActivityEntry action(List<RawEntry> members, RawEntry head, Integer step) {
        Map<String, Object> details = new LinkedHashMap<>();
        String text = null;
        for (RawEntry member : members) {
            Notes notes = notes(member.text());
            if (text == null) text = notes.text();
            for (Map.Entry<String, Object> fact : notes.details().entrySet()) merge(details, fact.getKey(), fact.getValue());
        }
        if (members.size() > 1) details.put("firstAt", members.get(members.size() - 1).at());
        return new ActivityEntry("action:" + head.id(), Kind.ACTION, head.action(), step, head.actorId(), null, head.at(), text,
                details, members.size());
    }

    private static void merge(Map<String, Object> details, String key, Object value) {
        Object existing = details.get(key);
        if (existing == null) {
            details.put(key, value instanceof Collection<?> list ? new ArrayList<>(list) : value);
        } else if (existing instanceof List<?> newer && value instanceof Collection<?> older) {
            Set<Object> united = new LinkedHashSet<>(newer);
            united.addAll(older);
            details.put(key, new ArrayList<>(united));
        }
    }

    private record Notes(String text, Map<String, Object> details) { }

    private Notes notes(String notes) {
        if (notes == null || notes.isBlank()) return new Notes(null, Map.of());
        String trimmed = notes.strip();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> facts = json.readValue(trimmed, JSON_OBJECT);
                String text = null;
                for (String key : TEXT_KEYS) {
                    if (facts.get(key) instanceof String value && !value.isBlank()) {
                        text = value;
                        break;
                    }
                }
                return new Notes(text, facts);
            } catch (RuntimeException notJson) {
                // a reason that happens to start with a brace is still a reason
            }
        }
        return new Notes(notes, Map.of());
    }

    private static Map<String, Object> callDetails(RawEntry row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contacted", row.contacted());
        details.put("outcome", row.outcome());
        if (row.contactLabel() != null) details.put("contactLabel", row.contactLabel());
        if (row.vendorId() != null) details.put("vendorId", row.vendorId());
        if (row.designerId() != null) details.put("designerId", row.designerId());
        if (row.nextFollowUpAt() != null) details.put("nextFollowUpAt", row.nextFollowUpAt());
        return details;
    }

    private static Map<String, Object> followUpDetails(RawEntry row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("percentComplete", row.percentComplete());
        if (row.nextFollowUpOn() != null) details.put("nextFollowUpOn", row.nextFollowUpOn());
        if (row.contactPhone() != null) details.put("contactPhone", row.contactPhone());
        return details;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
