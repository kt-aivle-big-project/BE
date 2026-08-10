package com.aivle.be.boardpost.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class S3BoardPostFileStorage implements BoardPostFileStorage {

    private final S3Client s3Client;

    @Value("${storage.s3.bucket}")
    private String bucket;

    @Override
    public String upload(Long postId, String fileName, String contentType, byte[] data) {
        String objectKey = "board-post-attachments/" + postId + "/" + UUID.randomUUID();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .metadata(Map.of("original-file-name", fileName))
                        .build(),
                RequestBody.fromBytes(data)
        );
        return objectKey;
    }

    @Override
    public byte[] download(String objectKey) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
        ).asByteArray();
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        s3Client.deleteObject(
                DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build()
        );
    }
}
