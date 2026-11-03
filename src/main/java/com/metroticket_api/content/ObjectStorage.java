package com.metroticket_api.content;

import java.util.Optional;

/** Lưu trữ đối tượng. Bản chạy thật là S3ObjectStorage; bật bằng app.storage.s3.enabled. */
public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    Optional<StoredObject> get(String key);

    void delete(String key);

    record StoredObject(byte[] content, String contentType) {
    }
}
