package com.custoking.ims.schoolcoreservice.erasure;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcsErasureJournalStoreTest {
    private final Storage storage = mock(Storage.class);
    private final GcsErasureJournalStore store = new GcsErasureJournalStore();
    GcsErasureJournalStoreTest() { ReflectionTestUtils.setField(store, "storage", storage); }

    @Test void onlyConfirmedNotFoundProducesTheAbsenceSignal() {
        when(storage.get("bucket", "object")).thenReturn(null);
        assertThatThrownBy(() -> store.readLatest("bucket", "object", 4096))
                .isInstanceOf(ErasureJournalStore.MissingObject.class);
        when(storage.get("bucket", "object")).thenThrow(new StorageException(403, "Access denied"));
        assertThatThrownBy(() -> store.readLatest("bucket", "object", 4096)).isInstanceOf(StorageException.class);
    }

    @Test void readsPinGenerationAndRefuseLargeMetadataBeforeFetchingContent() {
        Blob blob = mock(Blob.class);
        when(storage.get(BlobId.of("bucket", "object", 11L))).thenReturn(blob);
        when(blob.getGeneration()).thenReturn(11L); when(blob.getSize()).thenReturn(4097L);
        assertThatThrownBy(() -> store.readGeneration("bucket", "object", 11, 4096)).isInstanceOf(IllegalStateException.class);
        verify(blob, never()).getContent(any(Blob.BlobSourceOption.class));
        when(blob.getSize()).thenReturn(2L);
        when(blob.getContent(eq(Blob.BlobSourceOption.generationMatch()))).thenReturn(new byte[]{1, 2});
        var result = store.readGeneration("bucket", "object", 11, 4096);
        assertThat(result.generation()).isEqualTo(11);
        assertThat(result.body()).containsExactly((byte) 1, (byte) 2);
        when(blob.getContent(eq(Blob.BlobSourceOption.generationMatch()))).thenReturn(new byte[]{1});
        assertThatThrownBy(() -> store.readGeneration("bucket", "object", 11, 4096)).isInstanceOf(IllegalStateException.class);
    }

    @Test void createUsesGenerationZeroPreconditionAndRequiresProviderGeneration() {
        Blob blob = mock(Blob.class); byte[] body = new byte[]{1, 2};
        when(storage.create(any(BlobInfo.class), eq(body), eq(Storage.BlobTargetOption.doesNotExist()))).thenReturn(blob);
        when(blob.getGeneration()).thenReturn(11L);
        assertThat(store.createOnly("bucket", "object", body)).isEqualTo(11);
        verify(storage).create(any(BlobInfo.class), eq(body), eq(Storage.BlobTargetOption.doesNotExist()));
        when(blob.getGeneration()).thenReturn(null);
        assertThatThrownBy(() -> store.createOnly("bucket", "object", body)).isInstanceOf(IllegalStateException.class);
    }
}
