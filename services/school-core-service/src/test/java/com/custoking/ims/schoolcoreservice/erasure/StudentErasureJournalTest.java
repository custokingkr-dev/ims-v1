package com.custoking.ims.schoolcoreservice.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentErasureJournalTest {
    static final String LINEAGE = "68d28569-95ab-4288-ae34-3cb1183cc51b";
    static final String EPOCH = "f3ecac24-4a25-4a29-a087-45237c3117af";
    static final UUID INCARNATION = UUID.fromString("719f63db-a0f3-435f-818c-2a9fb3f0be5c");
    static final byte[] CONTROL = ("{\"database\":\"custoking_dev\",\"project\":\"custoking-dev\",\"restoreEpoch\":\"" + EPOCH
            + "\",\"schemaVersion\":1,\"sourceInstance\":\"custoking-db-dev\",\"sourceLineageId\":\"" + LINEAGE + "\",\"state\":\"ACTIVE\"}\n")
            .getBytes(StandardCharsets.US_ASCII);

    static ErasureJournalConfiguration configuration() {
        return new ErasureJournalConfiguration(true, "custoking-dev", "custoking-dev-erasure-journal", LINEAGE,
                EPOCH, 7, StudentErasureJournal.sha256(CONTROL));
    }

    @Test
    void stableAuthorizedRetryVerifiesTheExactImmutableIntent() {
        MemoryStore store = new MemoryStore();
        StudentErasureJournal journal = new StudentErasureJournal(configuration(), store);
        var first = journal.claim(301, 101, INCARNATION);
        var second = journal.claim(301, 101, INCARNATION);
        assertThat(second).isEqualTo(first);
        assertThat(first.intentId()).matches("[a-f0-9]{64}");
        assertThat(first.object()).isEqualTo("intents/" + LINEAGE + "/" + EPOCH + "/" + first.intentId() + ".json");
        assertThat(store.objects).hasSize(1);
        assertThat(store.pinnedReads).isEqualTo(1);
        assertThat(store.controlReads).isEqualTo(4);
        assertThat(new String(store.objects.get(first.object()).body(), StandardCharsets.US_ASCII))
                .contains("\"studentIncarnation\":\"" + INCARNATION + "\"")
                .doesNotContain("admission", "email", "phone", "name", "contact");
    }

    @Test
    void acceptanceFollowedByLostResponseRequiresMatchingReadback() {
        MemoryStore store = new MemoryStore(); store.loseCreateResponse = true;
        var receipt = new StudentErasureJournal(configuration(), store).claim(301, 101, INCARNATION);
        assertThat(receipt.generation()).isEqualTo(11);
        assertThat(store.objects).hasSize(1);
    }

    @Test
    void conflictWithDifferentContentFailsClosedWithoutExportingItsError() {
        MemoryStore store = new MemoryStore();
        StudentErasureJournal journal = new StudentErasureJournal(configuration(), store);
        var first = journal.claim(301, 101, INCARNATION);
        store.objects.put(first.object(), new ErasureJournalStore.StoredObject(11, "foreign".getBytes(StandardCharsets.US_ASCII)));
        assertUnavailable(() -> journal.claim(301, 101, INCARNATION));
    }

    @Test
    void oldEpochIsBlockedBeforeClaimAndEpochChangedAfterClaimLeavesIntentForReplay() {
        MemoryStore before = new MemoryStore(); before.controlGeneration = 8;
        assertUnavailable(() -> new StudentErasureJournal(configuration(), before).claim(301, 101, INCARNATION));
        assertThat(before.objects).isEmpty();
        MemoryStore during = new MemoryStore(); during.changeEpochAfterCreate = true;
        assertUnavailable(() -> new StudentErasureJournal(configuration(), during).claim(301, 101, INCARNATION));
        assertThat(during.objects).hasSize(1);
    }

    @Test
    void reusedStudentIdWithNewIncarnationCannotReuseOldIntent() {
        MemoryStore store = new MemoryStore(); var journal = new StudentErasureJournal(configuration(), store);
        var old = journal.claim(301, 101, INCARNATION);
        var replacement = journal.claim(301, 101, UUID.randomUUID());
        assertThat(replacement.intentId()).isNotEqualTo(old.intentId());
        assertThat(replacement.operationId()).isNotEqualTo(old.operationId());
        assertThat(store.objects).hasSize(2);
    }

    @Test
    void deliveryRequiresConfirmed404AndRejectsIntentPresenceEvenAfterLostCreateResponse() {
        MemoryStore store = new MemoryStore(); var journal = new StudentErasureJournal(configuration(), store);
        journal.requireDeliveryAllowed(301, 101, INCARNATION);
        store.loseCreateResponse = true;
        journal.claim(301, 101, INCARNATION);
        assertUnavailable(() -> journal.requireDeliveryAllowed(301, 101, INCARNATION));
        // A changed incarnation does not inherit the old target's intent.
        journal.requireDeliveryAllowed(301, 101, UUID.randomUUID());
    }

    @Test
    void deliveryCannotTreatPermissionOrMalformedResponsesAsAbsence() {
        MemoryStore store = new MemoryStore() {
            @Override public StoredObject readLatest(String bucket, String object, int limit) {
                if (object.startsWith("intents/")) throw new IllegalStateException("403 provider response");
                return super.readLatest(bucket, object, limit);
            }
        };
        assertUnavailable(() -> new StudentErasureJournal(configuration(), store).requireDeliveryAllowed(301, 101, INCARNATION));
        MemoryStore malformed = new MemoryStore() {
            @Override public StoredObject readLatest(String bucket, String object, int limit) {
                if (object.startsWith("intents/")) return new StoredObject(0, null);
                return super.readLatest(bucket, object, limit);
            }
        };
        assertUnavailable(() -> new StudentErasureJournal(configuration(), malformed).requireDeliveryAllowed(301, 101, INCARNATION));
    }

    @Test
    void replayRequiresPinnedCanonicalIntentAndAnExternallyReconcilingEpoch() {
        MemoryStore store = new MemoryStore(); var active = new StudentErasureJournal(configuration(), store);
        var original = active.claim(301, 101, INCARNATION);
        var reference = new StudentErasureJournal.IntentReference(original.object(), original.generation(), original.sha256());
        assertUnavailable(() -> active.verifyForReplay(reference));
        var replay = new StudentErasureJournal(reconcilingConfiguration(store), store);
        assertThat(replay.verifyForReplay(reference)).isEqualTo(original);
        assertUnavailable(() -> replay.claim(301, 101, INCARNATION));
        assertUnavailable(() -> replay.requireDeliveryAllowed(301, 101, UUID.randomUUID()));
        assertUnavailable(() -> replay.verifyForReplay(new StudentErasureJournal.IntentReference(original.object(), 11, "0".repeat(64))));
        assertUnavailable(() -> replay.verifyForReplay(new StudentErasureJournal.IntentReference("intents/" + LINEAGE + "/../control.json", 11, original.sha256())));
    }

    @Test
    void unconfiguredOrNonDevJournalCannotPublish() {
        MemoryStore store = new MemoryStore();
        var invalid = new ErasureJournalConfiguration(true, "custoking-prod", "custoking-dev-erasure-journal", LINEAGE,
                EPOCH, 7, StudentErasureJournal.sha256(CONTROL));
        assertUnavailable(() -> new StudentErasureJournal(invalid, store).claim(301, 101, INCARNATION));
        assertThat(store.controlReads).isZero();
        assertThat(store.objects).isEmpty();
    }

    @Test
    void completeClaimDeadlineRejectsAStalledPeer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore store = new MemoryStore() {
            @Override public StoredObject readLatest(String bucket, String object, int limit) {
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("secret provider payload must never escape");
            }
        };
        try {
            long start = System.nanoTime();
            assertUnavailable(() -> new StudentErasureJournal(configuration(), store, Duration.ofMillis(100)).claim(301, 101, INCARNATION));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(store.objects).isEmpty();
        } finally { release.countDown(); }
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503").hasMessageContaining("retry the authorized deletion")
                .hasNoCause();
    }

    static class MemoryStore implements ErasureJournalStore {
        final Map<String, StoredObject> objects = new HashMap<>();
        byte[] controlBody = CONTROL;
        long controlGeneration = 7; int controlReads, pinnedReads;
        boolean loseCreateResponse, changeEpochAfterCreate;
        @Override public StoredObject readLatest(String bucket, String object, int limit) {
            assertThat(bucket).isEqualTo("custoking-dev-erasure-journal");
            if (object.startsWith("control/")) { controlReads++; return new StoredObject(controlGeneration, controlBody); }
            StoredObject result = objects.get(object);
            if (result == null) throw new ErasureJournalStore.MissingObject();
            return result;
        }
        @Override public StoredObject readGeneration(String bucket, String object, long generation, int limit) {
            pinnedReads++; StoredObject result = readLatest(bucket, object, limit);
            assertThat(result.generation()).isEqualTo(generation); return result;
        }
        @Override public long createOnly(String bucket, String object, byte[] body) {
            if (objects.containsKey(object)) throw new IllegalStateException("412: secret object body");
            objects.put(object, new StoredObject(11, body));
            if (changeEpochAfterCreate) controlGeneration = 8;
            if (loseCreateResponse) throw new IllegalStateException("Uncertain response: secret object body");
            return 11;
        }
    }

    static ErasureJournalConfiguration reconcilingConfiguration(MemoryStore store) {
        String newEpoch = UUID.randomUUID().toString();
        store.controlBody = new String(CONTROL, StandardCharsets.US_ASCII).replace(EPOCH, newEpoch)
                .replace("ACTIVE", "RECONCILING").getBytes(StandardCharsets.US_ASCII);
        store.controlGeneration = 17;
        return new ErasureJournalConfiguration(true, "custoking-dev", "custoking-dev-erasure-journal", LINEAGE,
                newEpoch, 17, StudentErasureJournal.sha256(store.controlBody));
    }
}
