package com.custoking.ims.schoolcoreservice.erasure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Explicit dev-only opt-in. Production requires a separately reviewed lineage and contract. */
@Component
public class ErasureJournalConfiguration {
    public static final String PROJECT = "custoking-dev";
    public static final String SOURCE = "custoking-db-dev";
    public static final String DATABASE = "custoking_dev";
    public static final String BUCKET = "custoking-dev-erasure-journal";
    private final boolean enabled;
    private final String project, bucket, lineage, epoch, epochSha256;
    private final long epochGeneration;

    public ErasureJournalConfiguration(
            @Value("${student.erasure-journal.enabled:false}") boolean enabled,
            @Value("${student.erasure-journal.project-id:}") String project,
            @Value("${student.erasure-journal.bucket:}") String bucket,
            @Value("${student.erasure-journal.source-lineage-id:}") String lineage,
            @Value("${student.erasure-journal.restore-epoch:}") String epoch,
            @Value("${student.erasure-journal.epoch-generation:0}") long epochGeneration,
            @Value("${student.erasure-journal.epoch-sha256:}") String epochSha256) {
        this.enabled = enabled;
        this.project = clean(project); this.bucket = clean(bucket); this.lineage = clean(lineage);
        this.epoch = clean(epoch); this.epochGeneration = epochGeneration; this.epochSha256 = clean(epochSha256);
    }

    public boolean enabled() { return enabled; }
    public String bucket() { return bucket; }
    public String lineage() { return lineage; }
    public String epoch() { return epoch; }
    public long epochGeneration() { return epochGeneration; }
    public String epochSha256() { return epochSha256; }
    public String controlObject() { return "control/" + lineage + "/current.json"; }

    public boolean configured() {
        return enabled && PROJECT.equals(project) && BUCKET.equals(bucket)
                && lineage.matches("[a-z0-9][a-z0-9-]{7,63}") && canonicalUuid(epoch)
                && epochGeneration > 0 && epochSha256.matches("[a-f0-9]{64}");
    }

    private static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
