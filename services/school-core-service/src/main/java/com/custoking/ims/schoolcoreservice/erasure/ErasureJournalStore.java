package com.custoking.ims.schoolcoreservice.erasure;

/** No list, update or delete capability is exposed to the deletion runtime. */
public interface ErasureJournalStore {
    StoredObject readLatest(String bucket, String object, int limit);
    StoredObject readGeneration(String bucket, String object, long generation, int limit);
    long createOnly(String bucket, String object, byte[] body);

    /** Only a confirmed GCS404 may produce this signal; transport/auth/schema failures may not. */
    final class MissingObject extends RuntimeException {
        public MissingObject() { super("Journal object is not found"); }
    }

    record StoredObject(long generation, byte[] body) {
        public StoredObject { body = body == null ? null : body.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
    }
}
