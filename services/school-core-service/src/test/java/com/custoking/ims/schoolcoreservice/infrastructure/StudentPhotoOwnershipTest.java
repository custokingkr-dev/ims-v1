package com.custoking.ims.schoolcoreservice.infrastructure;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import com.google.auth.oauth2.ImpersonatedCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudentPhotoOwnershipTest {
    static final String UID="11111111-1111-4111-8111-111111111111";
    static final String KEY="schools/"+UID+"/students/42/photos/a.jpg";
    private StudentPhotoStorage photos(Storage sdk) {
        var result=new StudentPhotoStorage("private-bucket",5,512,5242880,"signer@example.test");
        ReflectionTestUtils.setField(result,"cleanupStorage",sdk);
        ReflectionTestUtils.setField(result,"storage",sdk);
        ReflectionTestUtils.setField(result,"signer",mock(ImpersonatedCredentials.class));
        return result;
    }
    @Test void foreignSchoolStudentAndTraversalNeverReachCloud() {
        var sdk=mock(Storage.class);var photos=photos(sdk);
        for(String key:new String[]{KEY.replace(UID,"22222222-2222-4222-8222-222222222222"),KEY.replace("/42/","/43/"),KEY.replace("/photos/","/../"),KEY.replace("/photos/","/%2e%2e/"),KEY.replace("/photos/","//photos/"),KEY.replace("/photos/","/documents/"),KEY.replace("a.jpg","nested/a.jpg")}) {
            assertThat(photos.readStoredPhoto(key,UID,42)).isEmpty();
            assertThat(photos.toDisplayUrl(key,UID,42)).isNull();
        }
        assertThat(photos.readStoredPhoto(KEY)).isEmpty();assertThat(photos.toDisplayUrl(KEY)).isNull();
        verifyNoInteractions(sdk);
    }
    @Test void encodedAndForeignLegacyUrlsCannotMintCapabilities() {
        var sdk=mock(Storage.class);var photos=photos(sdk);
        for(String url:new String[]{"https://storage.googleapis.com/private-bucket/"+KEY.replace("/42/","/43/"),"https://storage.googleapis.com/private-bucket/"+KEY.replace("/photos/","/%2e%2e/photos/"),"https://storage.googleapis.com/private-bucket/"+KEY.replace("/photos/","%2fphotos/"),"https://storage.googleapis.com.evil.test/private-bucket/"+KEY})
            assertThat(photos.toDisplayUrl(url,UID,42)).isNull();
        verifyNoInteractions(sdk);
    }
    @Test void exactOwnedUploadKeyCanBeSigned() throws Exception {
        var sdk=mock(Storage.class);var photos=photos(sdk);
        when(sdk.signUrl(any(BlobInfo.class),eq(5L),eq(java.util.concurrent.TimeUnit.MINUTES),any(Storage.SignUrlOption[].class)))
                .thenReturn(URI.create("https://storage.googleapis.com/private-bucket/"+KEY+"?safe-test-signature").toURL());
        assertThat(photos.toDisplayUrl(KEY,UID,42)).contains("safe-test-signature");
        var info=org.mockito.ArgumentCaptor.forClass(BlobInfo.class);verify(sdk).signUrl(info.capture(),eq(5L),eq(java.util.concurrent.TimeUnit.MINUTES),any(Storage.SignUrlOption[].class));
        assertThat(info.getValue().getName()).isEqualTo(KEY);
    }
    private Blob metadata(Storage sdk,long size) {
        var blob=mock(Blob.class);when(blob.getGeneration()).thenReturn(17L);when(blob.getSize()).thenReturn(size);when(blob.getContentType()).thenReturn("image/jpeg");
        when(sdk.get(any(BlobId.class),any(Storage.BlobGetOption[].class))).thenReturn(blob);return blob;
    }
    @Test void ownedReadPinsObservedGenerationAndExactSize() throws Exception {
        var sdk=mock(Storage.class);var photos=photos(sdk);metadata(sdk,3);
        var reader=mock(ReadChannel.class);var calls=new AtomicInteger();
        when(reader.read(any(ByteBuffer.class))).thenAnswer(inv->{if(calls.getAndIncrement()>0)return -1;((ByteBuffer)inv.getArgument(0)).put(new byte[]{1,2,3});return 3;});
        when(sdk.reader(any(BlobId.class),any(Storage.BlobSourceOption[].class))).thenReturn(reader);
        assertThat(photos.readStoredPhoto(KEY,UID,42).orElseThrow().data()).containsExactly(1,2,3);
        var id=org.mockito.ArgumentCaptor.forClass(BlobId.class);verify(sdk).reader(id.capture(),any(Storage.BlobSourceOption[].class));
        assertThat(id.getValue().getName()).isEqualTo(KEY);assertThat(id.getValue().getGeneration()).isEqualTo(17L);verify(reader).close();
    }
    @Test void oversizedMetadataPreventsContentRead() {
        var sdk=mock(Storage.class);var photos=photos(sdk);metadata(sdk,5242881);
        assertThat(photos.readStoredPhoto(KEY,UID,42)).isEmpty();verify(sdk,never()).reader(any(BlobId.class),any(Storage.BlobSourceOption[].class));
    }
    @Test void streamCannotExceedMetadataOrConfiguredLimit() throws Exception {
        var sdk=mock(Storage.class);var photos=photos(sdk);metadata(sdk,1);
        var reader=mock(ReadChannel.class);var calls=new AtomicInteger();when(reader.read(any(ByteBuffer.class))).thenAnswer(inv->{if(calls.getAndIncrement()>0)return -1;((ByteBuffer)inv.getArgument(0)).put(new byte[]{1,2});return 2;});
        when(sdk.reader(any(BlobId.class),any(Storage.BlobSourceOption[].class))).thenReturn(reader);
        assertThat(photos.readStoredPhoto(KEY,UID,42)).isEmpty();verify(reader).close();
    }
    @Test void replacedGenerationIsNotReadByFollowingLatest() {
        var sdk=mock(Storage.class);var photos=photos(sdk);metadata(sdk,3);
        when(sdk.reader(any(BlobId.class),any(Storage.BlobSourceOption[].class)))
                .thenThrow(new StorageException(412,"controlled generation replacement"));
        assertThat(photos.readStoredPhoto(KEY,UID,42)).isEmpty();
        verify(sdk,times(1)).get(any(BlobId.class),any(Storage.BlobGetOption[].class));
        verify(sdk).reader(eq(BlobId.of("private-bucket",KEY,17L)),eq(Storage.BlobSourceOption.generationMatch(17L)));
    }
    @Test void activeDocumentMetadataIsNotServedAsAPhoto() {
        var sdk=mock(Storage.class);var photos=photos(sdk);var blob=metadata(sdk,3);
        when(blob.getContentType()).thenReturn("text/html");
        assertThat(photos.readStoredPhoto(KEY,UID,42)).isEmpty();
        verify(sdk,never()).reader(any(BlobId.class),any(Storage.BlobSourceOption[].class));
    }
    @Test @Timeout(11) void completeCallDeadlineIncludesMetadata() {
        var sdk=mock(Storage.class);var photos=photos(sdk);
        when(sdk.get(any(BlobId.class),any(Storage.BlobGetOption[].class))).thenAnswer(inv->{Thread.sleep(20000);return null;});
        long start=System.nanoTime();assertThat(photos.readStoredPhoto(KEY,UID,42)).isEmpty();
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(9500);
    }
}
