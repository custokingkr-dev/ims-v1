package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.ActivityService.ActivityEntry;
import com.custoking.ims.operationsservice.application.ActivityService.ActivityPage;
import com.custoking.ims.operationsservice.application.ActivityService.Kind;
import com.custoking.ims.operationsservice.persistence.ActivityRepository;
import com.custoking.ims.operationsservice.persistence.ActivityRepository.RawEntry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The activity timeline's pure parts (design D40): grouping consecutive saves, cutting pages at whole timestamps so
 * {@code before} paging never skips or repeats an entry, and turning each source's rows into entries.
 */
class ActivityServiceTest {
    static final OffsetDateTime T = OffsetDateTime.parse("2026-11-13T10:00:00Z");
    static final ActivityService SERVICE = new ActivityService(null, null, null, null, null, null, JsonMapper.builder().build());

    static RawEntry action(long id, String action, Integer step, long actor, OffsetDateTime at, String notes) {
        return new RawEntry(ActivityRepository.ACTION, id, step, actor, at, action, notes, null, null, null, null, null, null, null, null, null);
    }

    static RawEntry save(long id, int step, long actor, OffsetDateTime at) {
        return action(id, "STEP_DATA_SAVED", step, actor, at, null);
    }

    static OffsetDateTime at(int minutes) { return T.plusMinutes(minutes); }

    // ---- grouping ------------------------------------------------------------------------------------------------------

    @Test void consecutiveSavesByOnePersonOnOneStageWithinTenMinutesAreOneEntry() {
        List<RawEntry> rows = List.of(
                save(1, 2, 7, at(20)), save(2, 2, 7, at(15)), save(3, 2, 7, at(10)),   // within 10 minutes of the newest
                save(4, 2, 7, at(9)),                                                // 11 minutes before the newest: a new entry
                save(5, 2, 8, at(8)),                                                // someone else
                save(6, 3, 8, at(7)),                                                // another stage
                action(7, "STEP_COMPLETED", 3, 8, at(6), null),                      // not a save
                save(8, 3, 8, at(5)));                                               // after a non-save: a new entry

        List<List<RawEntry>> groups = ActivityService.group(rows);
        assertEquals(List.of(List.of(1L, 2L, 3L), List.of(4L), List.of(5L), List.of(6L), List.of(7L), List.of(8L)),
                groups.stream().map(group -> group.stream().map(RawEntry::id).toList()).toList());
    }

    @Test void onlyDataSavesAreGrouped() {
        List<RawEntry> rows = List.of(action(1, "VENDORS_SELECTED", 5, 7, at(3), null), action(2, "VENDORS_SELECTED", 5, 7, at(2), null),
                new RawEntry(ActivityRepository.NOTE, 3, 5, 7L, at(1), null, "note", null, null, null, null, null, null, null, null, null),
                new RawEntry(ActivityRepository.NOTE, 4, 5, 7L, at(0), null, "note", null, null, null, null, null, null, null, null, null));
        assertEquals(4, ActivityService.group(rows).size());
    }

    // ---- paging --------------------------------------------------------------------------------------------------------

    /** The repository's contract: rows strictly older than the cursor, newest first, at least a batch, whole timestamps. */
    static ActivityService.Batches source(List<RawEntry> all, int batchSize, List<OffsetDateTime> cursors) {
        return cursor -> {
            cursors.add(cursor);
            List<RawEntry> older = all.stream().filter(row -> cursor == null || row.at().isBefore(cursor)).toList();
            if (older.size() <= batchSize) return older;
            OffsetDateTime last = older.get(batchSize - 1).at();
            return older.stream().filter(row -> !row.at().isBefore(last)).toList();
        };
    }

    static ActivityPage page(List<RawEntry> all, OffsetDateTime before, int limit, int batchSize) {
        return ActivityService.page(source(all, batchSize, new ArrayList<>()), before, limit, batchSize, SERVICE::entry);
    }

    /** Every page in turn, following nextBefore. */
    static List<List<String>> pages(List<RawEntry> all, int limit, int batchSize) {
        List<List<String>> pages = new ArrayList<>();
        OffsetDateTime before = null;
        for (int guard = 0; guard < 10_000; guard++) {
            ActivityPage page = page(all, before, limit, batchSize);
            if (!page.entries().isEmpty()) pages.add(page.entries().stream().map(e -> e.id() + "x" + e.count()).toList());
            if (page.nextBefore() == null) return pages;
            assertTrue(before == null || page.nextBefore().isBefore(before), "pages move strictly back in time");
            before = page.nextBefore();
        }
        throw new AssertionError("paging did not end");
    }

    @Test void aPageEndsAtItsLimitAndTheNextOneStartsStrictlyOlder() {
        List<RawEntry> rows = List.of(save(1, 1, 7, at(50)), action(2, "STEP_COMPLETED", 1, 7, at(40), null),
                action(3, "STEP_ASSIGNED", 2, 1, at(30), null), action(4, "TRACKER_CREATED", 1, 1, at(20), null));
        ActivityPage first = page(rows, null, 2, 200);
        assertEquals(List.of("action:1", "action:2"), first.entries().stream().map(ActivityEntry::id).toList());
        assertEquals(at(40), first.nextBefore());
        ActivityPage second = page(rows, first.nextBefore(), 2, 200);
        assertEquals(List.of("action:3", "action:4"), second.entries().stream().map(ActivityEntry::id).toList());
        assertNull(second.nextBefore(), "nothing older remains");
        ActivityPage empty = page(List.of(), null, 2, 200);
        assertTrue(empty.entries().isEmpty());
        assertNull(empty.nextBefore());
    }

    @Test void entriesSharingThePagesOldestTimeAreAllOnThatPage() {
        List<RawEntry> rows = List.of(action(1, "HEAD_APPROVED", 4, 1, at(30), null), action(2, "STEP_COMPLETED", 4, 1, at(20), null),
                action(3, "STEP_ASSIGNED", 5, 1, at(20), null), action(4, "STEP_STARTED", 5, 1, at(20), null),
                action(5, "TRACKER_CREATED", 1, 1, at(10), null));
        ActivityPage first = page(rows, null, 2, 2);
        assertEquals(List.of("action:1", "action:2", "action:3", "action:4"), first.entries().stream().map(ActivityEntry::id).toList(),
                "the page runs over its limit rather than split a moment");
        assertEquals(at(20), first.nextBefore());
        assertEquals(List.of("action:5"), page(rows, first.nextBefore(), 2, 2).entries().stream().map(ActivityEntry::id).toList());
    }

    @Test void aGroupOfSavesIsNeverSplitAcrossBatchesOrPages() {
        List<RawEntry> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(save(10 + i, 2, 7, at(30 - i)));
        rows.add(action(20, "STEP_COMPLETED", 1, 7, at(5), null));
        List<OffsetDateTime> cursors = new ArrayList<>();

        ActivityPage first = ActivityService.page(source(rows, 2, cursors), null, 1, 2, SERVICE::entry);
        assertEquals(1, first.entries().size());
        assertEquals(7, first.entries().get(0).count(), "seven saves read over four batches are still one entry");
        assertEquals(at(30), first.entries().get(0).at(), "a group is dated by its newest save");
        assertEquals(at(24), first.entries().get(0).details().get("firstAt"));
        assertEquals(at(24), first.nextBefore());
        assertTrue(cursors.size() >= 4, "it read on until the group was complete: " + cursors);
    }

    @Test void pagingThroughAnyMixOfRowsGivesExactlyTheUnpagedTimelineInOrder() {
        Random random = new Random(20261113);
        for (int round = 0; round < 40; round++) {
            List<RawEntry> rows = new ArrayList<>();
            OffsetDateTime time = T;
            long id = 1;
            int count = random.nextInt(60);
            for (int i = 0; i < count; i++) {
                if (random.nextInt(3) > 0) time = time.minusSeconds(1 + random.nextInt(400)); // otherwise the same instant
                boolean saving = random.nextInt(2) == 0;
                long actor = 1 + random.nextInt(2);
                int step = 1 + random.nextInt(2);
                rows.add(saving ? save(id++, step, actor, time) : action(id++, "STEP_ASSIGNED", step, actor, time, null));
            }
            rows.sort(Comparator.comparing(RawEntry::at).reversed().thenComparing(RawEntry::kind)
                    .thenComparing(Comparator.comparingLong(RawEntry::id).reversed()));
            List<String> unpaged = page(rows, null, 100_000, 100_000).entries().stream().map(e -> e.id() + "x" + e.count()).toList();
            for (int limit : new int[] {1, 2, 3, 7}) {
                for (int batch : new int[] {1, 2, 5, 200}) {
                    List<String> paged = pages(rows, limit, batch).stream().flatMap(List::stream).toList();
                    assertEquals(unpaged, paged, "round " + round + ", limit " + limit + ", batch " + batch);
                }
            }
        }
    }

    // ---- entries -------------------------------------------------------------------------------------------------------

    @Test void anActionsJsonNotesAreItsDetailsAndItsReasonOrCommentIsItsText() {
        ActivityEntry reopened = SERVICE.entry(List.of(action(5, "STEP_REOPENED", 3, 1, at(0),
                "{\"reason\":\"Wrong CDR uploaded\",\"from\":4}")));
        assertEquals("action:5", reopened.id());
        assertEquals(Kind.ACTION, reopened.kind());
        assertEquals("STEP_REOPENED", reopened.action());
        assertEquals(3, reopened.stepOrder());
        assertEquals(1L, reopened.actorUserId());
        assertEquals("Wrong CDR uploaded", reopened.text());
        assertEquals(Map.of("reason", "Wrong CDR uploaded", "from", 4), reopened.details());
        assertEquals(1, reopened.count());

        ActivityEntry approved = SERVICE.entry(List.of(action(6, "HEAD_APPROVED", 4, 1, at(0), "{\"comment\":\"Looks right\"}")));
        assertEquals("Looks right", approved.text());
        ActivityEntry plain = SERVICE.entry(List.of(action(7, "SENT_BACK", 7, 1, at(0), "Margins are off")));
        assertEquals("Margins are off", plain.text());
        assertEquals(Map.of(), plain.details());
        ActivityEntry brace = SERVICE.entry(List.of(action(8, "SENT_BACK", 7, 1, at(0), "{not json} but a reason")));
        assertEquals("{not json} but a reason", brace.text(), "a reason that starts with a brace is still a reason");
        assertNull(SERVICE.entry(List.of(action(9, "TRACKER_CREATED", 0, 1, at(0), null))).stepOrder(), "an order-level action has no stage");
    }

    @Test void aGroupOfSavesUnitesTheChangedFieldsAndKeepsTheNewestCounts() {
        ActivityEntry group = SERVICE.entry(List.of(
                action(3, "STEP_DATA_SAVED", 7, 9, at(9), "{\"fields\":[\"sampleLink\"],\"checked\":3}"),
                action(2, "STEP_DATA_SAVED", 7, 9, at(5), "{\"fields\":[\"qcComments\",\"sampleLink\"],\"checked\":2}"),
                action(1, "STEP_DATA_SAVED", 7, 9, at(1), "{\"checked\":1}")));
        assertEquals("action:3", group.id(), "a group is known by its newest save");
        assertEquals(3, group.count());
        assertEquals(at(9), group.at());
        assertEquals(List.of("sampleLink", "qcComments"), group.details().get("fields"));
        assertEquals(3, group.details().get("checked"));
        assertEquals(at(1), group.details().get("firstAt"));
        assertNull(group.text());
    }

    @Test void notesCallsAndFollowUpsBecomeEntriesOfTheirKind() {
        ActivityEntry stage = SERVICE.entry(List.of(new RawEntry(ActivityRepository.NOTE, 5, 6, 9L, at(0), null, "Boards booked",
                null, null, null, null, null, null, null, null, null)));
        assertEquals("note:5", stage.id());
        assertEquals(Kind.NOTE, stage.kind());
        assertEquals("Boards booked", stage.text());
        assertEquals(Map.of("type", "STAGE"), stage.details());
        ActivityEntry internal = SERVICE.entry(List.of(new RawEntry(ActivityRepository.NOTE, 6, null, 9L, at(0), null, "Called back",
                null, null, null, null, null, null, null, null, null)));
        assertEquals(Map.of("type", "INTERNAL"), internal.details());
        assertNull(internal.stepOrder());

        OffsetDateTime followUp = at(600);
        ActivityEntry call = SERVICE.entry(List.of(new RawEntry(ActivityRepository.CALL, 3, 5, 9L, at(0), null, "Call after lunch",
                "SCHOOL", "CALLBACK_REQUESTED", "Mrs Rao", null, null, followUp, null, null, null)));
        assertEquals("call:3", call.id());
        assertEquals(Kind.CALL, call.kind());
        assertNull(call.action());
        assertEquals("Call after lunch", call.text());
        assertEquals(Map.of("contacted", "SCHOOL", "outcome", "CALLBACK_REQUESTED", "contactLabel", "Mrs Rao", "nextFollowUpAt", followUp),
                call.details());
        ActivityEntry vendorCall = SERVICE.entry(List.of(new RawEntry(ActivityRepository.CALL, 4, 8, 9L, at(0), null, null,
                "PRINTING_PRESS", "CONNECTED", null, 12L, null, null, null, null, null)));
        assertEquals(Map.of("contacted", "PRINTING_PRESS", "outcome", "CONNECTED", "vendorId", 12L), vendorCall.details());

        ActivityEntry legacy = SERVICE.entry(List.of(new RawEntry(ActivityRepository.FOLLOW_UP, 2, 10, 9L, at(0), null, null, null, null,
                null, null, null, null, 40, LocalDate.parse("2026-11-20"), "+91 98765 43210")));
        assertEquals("follow-up:2", legacy.id());
        assertEquals(Kind.FOLLOW_UP, legacy.kind());
        assertEquals(10, legacy.stepOrder());
        assertNull(legacy.text());
        assertEquals(Map.of("percentComplete", 40, "nextFollowUpOn", LocalDate.parse("2026-11-20"), "contactPhone", "+91 98765 43210"),
                legacy.details());
    }
}
