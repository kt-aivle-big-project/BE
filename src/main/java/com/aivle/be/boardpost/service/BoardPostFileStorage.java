package com.aivle.be.boardpost.service;

public interface BoardPostFileStorage {
    String upload(Long postId, String fileName, String contentType, byte[] data);
    byte[] download(String objectKey);
    void delete(String objectKey);
}
