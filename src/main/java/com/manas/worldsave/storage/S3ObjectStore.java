package com.manas.worldsave.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Real S3-compatible object store, talking to a real bucket over the real
 * AWS SDK v2 S3Client. Each chunk is stored as its own S3 object under
 * key "{objectKey}/chunk-{chunkIndex}", which is what makes chunked upload
 * genuinely resumable: a partial upload leaves exactly the chunks that
 * succeeded as independently readable objects, nothing more.
 */
public class S3ObjectStore implements ObjectStore {

    private final S3Client s3Client;
    private final String bucket;

    public S3ObjectStore(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    private String key(String objectKey, int chunkIndex) {
        return objectKey + "/chunk-" + chunkIndex;
    }

    @Override
    public void putChunk(String objectKey, int chunkIndex, byte[] data) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key(objectKey, chunkIndex)).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public byte[] getChunk(String objectKey, int chunkIndex) {
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key(objectKey, chunkIndex)).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new NoSuchChunkException(objectKey, chunkIndex);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean hasChunk(String objectKey, int chunkIndex) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(objectKey, chunkIndex)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void deleteObject(String objectKey, int chunkCountUpperBound) {
        List<ObjectIdentifier> ids = new ArrayList<>();
        for (int i = 0; i < chunkCountUpperBound; i++) {
            ids.add(ObjectIdentifier.builder().key(key(objectKey, i)).build());
        }
        for (ObjectIdentifier id : ids) {
            try {
                s3Client.deleteObject(builder -> builder.bucket(bucket).key(id.key()));
            } catch (S3Exception ignored) {
                // best-effort cleanup
            }
        }
    }
}
