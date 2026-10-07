package com.custoking.ims.schoolcoreservice.erasure;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Fixed GCS transport; caller configuration never selects an endpoint or credential source. */
@Component
public class GcsErasureJournalStore implements ErasureJournalStore {
    private volatile Storage storage;

    @Override public StoredObject readLatest(String bucket, String object, int limit) {
        return read(client().get(bucket, object), limit);
    }

    @Override public StoredObject readGeneration(String bucket, String object, long generation, int limit) {
        return read(client().get(BlobId.of(bucket, object, generation)), limit);
    }

    @Override public long createOnly(String bucket, String object, byte[] body) {
        Blob blob = client().create(BlobInfo.newBuilder(bucket, object)
                .setContentType("application/json").setCacheControl("private, no-store").build(),
                body, Storage.BlobTargetOption.doesNotExist());
        if (blob == null || blob.getGeneration() == null || blob.getGeneration() <= 0) {
            throw new IllegalStateException("Journal create receipt is unavailable");
        }
        return blob.getGeneration();
    }

    private StoredObject read(Blob blob, int limit) {
        // Storage.get returns null only for404; all other failures throw and remain fail-closed.
        if (blob == null) throw new ErasureJournalStore.MissingObject();
        if (blob.getGeneration() == null || blob.getGeneration() <= 0
                || blob.getSize() == null || blob.getSize() < 1 || blob.getSize() > limit) {
            throw new IllegalStateException("Journal object is missing or too large");
        }
        byte[] bytes = blob.getContent(Blob.BlobSourceOption.generationMatch());
        if (bytes.length != blob.getSize() || bytes.length > limit) {
            throw new IllegalStateException("Journal object size does not match");
        }
        return new StoredObject(blob.getGeneration(), bytes);
    }

    private Storage client() {
        if (storage == null) synchronized (this) {
            if (storage == null) storage = StorageOptions.http().setProjectId(ErasureJournalConfiguration.PROJECT)
                    .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(3000).setReadTimeout(5000).build())
                    .setRetrySettings(RetrySettings.newBuilder()
                            .setTotalTimeoutDuration(Duration.ofSeconds(8)).setMaxAttempts(1)
                            .setInitialRpcTimeoutDuration(Duration.ofSeconds(5)).setMaxRpcTimeoutDuration(Duration.ofSeconds(5)).setRpcTimeoutMultiplier(1)
                            .setInitialRetryDelayDuration(Duration.ofMillis(200)).setMaxRetryDelayDuration(Duration.ofSeconds(1)).setRetryDelayMultiplier(2)
                            .build()).build().getService();
        }
        return storage;
    }
}
