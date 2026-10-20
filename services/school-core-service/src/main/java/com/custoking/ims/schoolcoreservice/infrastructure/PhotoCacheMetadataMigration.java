package com.custoking.ims.schoolcoreservice.infrastructure;

/** Explicit operator entry point; dry-run by default. Never runs implicitly at service startup. */
public final class PhotoCacheMetadataMigration {
    private PhotoCacheMetadataMigration() {}
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !"--apply".equals(args[1])))
            throw new IllegalArgumentException("Usage: PhotoCacheMetadataMigration BUCKET [--apply]");
        var storage = com.google.cloud.storage.StorageOptions.getDefaultInstance().getService();
        boolean apply = args.length == 2;
        long candidates = 0, updated = 0;
        for (var blob : storage.list(args[0], com.google.cloud.storage.Storage.BlobListOption.prefix("schools/"),
                com.google.cloud.storage.Storage.BlobListOption.pageSize(100)).iterateAll()) {
            if (!blob.getName().contains("/students/") || !blob.getName().contains("/photos/")) continue;
            if ("private, max-age=0, no-store".equals(blob.getCacheControl())) continue;
            candidates++;
            if (apply) {
                storage.update(blob.toBuilder().setCacheControl("private, max-age=0, no-store").build(),
                        com.google.cloud.storage.Storage.BlobTargetOption.metagenerationMatch());
                updated++;
            }
        }
        System.out.printf("mode=%s candidate_objects=%d updated_objects=%d%n", apply ? "apply" : "dry-run", candidates, updated);
    }
}
