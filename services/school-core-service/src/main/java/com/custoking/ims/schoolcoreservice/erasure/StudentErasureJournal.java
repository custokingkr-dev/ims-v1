package com.custoking.ims.schoolcoreservice.erasure;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * An authenticated, confirmed DELETE irreversibly claims intent before source mutation. A SQL
 * rollback does not cancel that external intent; restore reconciliation must finish its erasure.
 */
@Component
public class StudentErasureJournal {
    private static final int INTENT_LIMIT = 4096;
    private final ErasureJournalConfiguration configuration;
    private final ErasureJournalStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration deadline;
    private final Semaphore capacity = new Semaphore(2);
    private final java.util.concurrent.ExecutorService requests = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("erasure-journal-", 0).factory());

    @Autowired
    public StudentErasureJournal(ErasureJournalConfiguration configuration, ErasureJournalStore store) {
        this(configuration, store, Duration.ofSeconds(25));
    }

    StudentErasureJournal(ErasureJournalConfiguration configuration, ErasureJournalStore store, Duration deadline) {
        this.configuration = configuration; this.store = store; this.deadline = deadline;
    }

    public boolean enabled() { return configuration.enabled(); }

    /** The JDBC owner must independently bind its connection before any external operation. */
    public void requireSourceDatabase(String database) {
        if (enabled() && (!configuration.configured() || !ErasureJournalConfiguration.DATABASE.equals(database))) {
            throw unavailable();
        }
    }

    public Receipt claim(long studentId, long schoolId, UUID incarnation) {
        if (!configuration.configured() || studentId <= 0 || schoolId <= 0 || incarnation == null) throw unavailable();
        return bounded(() -> claimBounded(studentId, schoolId, incarnation), deadline);
    }

    /** Existing, malformed or unconfirmed external intent always prevents guardian delivery. */
    public void requireDeliveryAllowed(long studentId, long schoolId, UUID incarnation) {
        if (!enabled()) return;
        if (!configuration.configured() || studentId <= 0 || schoolId <= 0 || incarnation == null) throw unavailable();
        bounded(() -> {
            requireActiveEpoch();
            String intentId = sha256(canonical(identity(studentId, schoolId, incarnation, configuration.epoch())));
            try {
                store.readLatest(configuration.bucket(), intentObject(configuration.epoch(), intentId), INTENT_LIMIT);
            } catch (ErasureJournalStore.MissingObject confirmed404) {
                requireActiveEpoch();
                return null;
            }
            throw unavailable(); // Presence is a fence even when its body cannot be decoded.
        }, Duration.ofSeconds(4));
    }

    /** Owner-only application capability; no HTTP route or delivery-resume authority is added. */
    public Receipt verifyForReplay(IntentReference reference) {
        if (!configuration.configured() || reference == null || reference.generation() <= 0
                || reference.object() == null || reference.object().length() > 512
                || reference.sha256() == null || !reference.sha256().matches("[a-f0-9]{64}")) throw unavailable();
        return bounded(() -> {
            requireEpoch("RECONCILING");
            String prefix = "intents/" + configuration.lineage() + "/";
            if (!reference.object().startsWith(prefix) || !reference.object().substring(prefix.length())
                    .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/[a-f0-9]{64}\\.json")) throw unavailable();
            var observed = store.readGeneration(configuration.bucket(), reference.object(), reference.generation(), INTENT_LIMIT);
            if (observed == null || observed.generation() != reference.generation()
                    || observed.body() == null || !sha256(observed.body()).equals(reference.sha256())) throw unavailable();
            var payload = mapper.readTree(observed.body());
            if (!payload.path("studentId").isIntegralNumber() || !payload.path("schoolId").isIntegralNumber()) throw unavailable();
            long studentId = payload.path("studentId").asLong(), schoolId = payload.path("schoolId").asLong();
            UUID incarnation = UUID.fromString(payload.path("studentIncarnation").asText());
            UUID originalEpoch = UUID.fromString(payload.path("restoreEpoch").asText());
            if (studentId <= 0 || schoolId <= 0) throw unavailable();
            Map<String, Object> identity = identity(studentId, schoolId, incarnation, originalEpoch.toString());
            String intentId = sha256(canonical(identity));
            UUID operation = operationId(intentId);
            Map<String, Object> expected = intentPayload(identity, intentId, operation);
            if (!Arrays.equals(canonical(expected), observed.body())
                    || !intentObject(originalEpoch.toString(), intentId).equals(reference.object())) throw unavailable();
            requireEpoch("RECONCILING");
            return new Receipt(intentId, operation, studentId, schoolId, incarnation, configuration.lineage(), originalEpoch,
                    configuration.bucket(), reference.object(), reference.generation(), reference.sha256());
        }, deadline);
    }

    private <T> T bounded(java.util.concurrent.Callable<T> operation, Duration limit) {
        if (!capacity.tryAcquire()) throw unavailable();
        var future = requests.submit(() -> {
            try { return operation.call(); }
            finally { capacity.release(); }
        });
        try { return future.get(limit.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); future.cancel(true); throw unavailable();
        } catch (Exception unconfirmed) {
            // Never surface transport errors, credentials or provider bodies. A late create is an
            // authorized intent only; this task never mutates SQL after the caller has timed out.
            future.cancel(true); throw unavailable();
        }
    }

    private Receipt claimBounded(long studentId, long schoolId, UUID incarnation) {
        requireActiveEpoch();
        Map<String, Object> identity = identity(studentId, schoolId, incarnation, configuration.epoch());
        String intentId = sha256(canonical(identity));
        UUID operation = operationId(intentId);
        Map<String, Object> payload = intentPayload(identity, intentId, operation);
        byte[] body = canonical(payload);
        String object = intentObject(configuration.epoch(), intentId);
        ErasureJournalStore.StoredObject observed;
        try {
            long generation = store.createOnly(configuration.bucket(), object, body);
            observed = store.readGeneration(configuration.bucket(), object, generation, INTENT_LIMIT);
            if (observed.generation() != generation) throw unavailable();
        } catch (RuntimeException createUnconfirmed) {
            // A412 conflict and acceptance followed by a lost response both require exact
            // generation/content readback. Status alone can never establish successful intent.
            observed = store.readLatest(configuration.bucket(), object, INTENT_LIMIT);
        }
        if (observed == null || observed.generation() <= 0 || !Arrays.equals(body, observed.body())) throw unavailable();
        requireActiveEpoch();
        return new Receipt(intentId, operation, studentId, schoolId, incarnation, configuration.lineage(),
                UUID.fromString(configuration.epoch()), configuration.bucket(), object, observed.generation(), sha256(body));
    }

    private void requireActiveEpoch() {
        requireEpoch("ACTIVE");
    }

    private void requireEpoch(String state) {
        Map<String, Object> control = sourceContext(); control.put("state", state);
        byte[] expected = canonical(control);
        var actual = store.readLatest(configuration.bucket(), configuration.controlObject(), 2048);
        if (actual == null || actual.generation() != configuration.epochGeneration()
                || !Arrays.equals(expected, actual.body())
                || !sha256(expected).equals(configuration.epochSha256())) throw unavailable();
    }

    private Map<String, Object> sourceContext() {
        Map<String, Object> value = new TreeMap<>();
        value.put("schemaVersion", 1); value.put("project", ErasureJournalConfiguration.PROJECT);
        value.put("sourceInstance", ErasureJournalConfiguration.SOURCE); value.put("database", ErasureJournalConfiguration.DATABASE);
        value.put("sourceLineageId", configuration.lineage()); value.put("restoreEpoch", configuration.epoch());
        return value;
    }

    private Map<String, Object> identity(long studentId, long schoolId, UUID incarnation, String epoch) {
        Map<String, Object> identity = sourceContext(); identity.put("restoreEpoch", epoch);
        identity.put("schoolId", schoolId); identity.put("studentId", studentId);
        identity.put("studentIncarnation", incarnation.toString());
        return identity;
    }

    private String intentObject(String epoch, String intentId) {
        return "intents/" + configuration.lineage() + "/" + epoch + "/" + intentId + ".json";
    }

    private static UUID operationId(String intentId) {
        return UUID.nameUUIDFromBytes(("ims-student-erasure:" + intentId).getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> intentPayload(Map<String, Object> identity, String intentId, UUID operation) {
        Map<String, Object> payload = new TreeMap<>(identity);
        payload.put("intentId", intentId); payload.put("operationId", operation.toString());
        payload.put("kind", "student.erasure-intent.v1");
        return payload;
    }

    private byte[] canonical(Map<String, Object> value) {
        return (mapper.writeValueAsString(new TreeMap<>(value)) + "\n").getBytes(StandardCharsets.US_ASCII);
    }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256 is unavailable"); }
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Erasure journal is unavailable; retry the authorized deletion");
    }

    public record Receipt(String intentId, UUID operationId, long studentId, long schoolId, UUID incarnation,
                          String sourceLineageId, UUID restoreEpoch, String bucket, String object,
                          long generation, String sha256) {
        public Map<String, Object> eventEvidence() {
            return Map.of("erasureIntentId", intentId, "erasureOperationId", operationId.toString(),
                    "studentIncarnation", incarnation.toString(), "sourceLineageId", sourceLineageId,
                    "restoreEpoch", restoreEpoch.toString(), "erasureJournalObject", object,
                    "erasureJournalGeneration", generation, "erasureJournalSha256", sha256);
        }
    }

    public record IntentReference(String object, long generation, String sha256) {}
}
