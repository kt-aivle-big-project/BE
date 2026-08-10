package com.aivle.be.boardpost.controller.response;

import com.aivle.be.boardpost.entity.BoardPost;

import java.time.LocalDateTime;

public record BoardPostResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        String authorName,
        AttachmentResponse attachment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean mine
) {
    public static BoardPostResponse from(BoardPost boardPost, Long currentUserId) {
        return new BoardPostResponse(
                boardPost.getId(),
                boardPost.getTitle(),
                boardPost.getContent(),
                boardPost.getAuthor() == null ? null : boardPost.getAuthor().getId(),
                boardPost.getAuthor() == null ? "탈퇴한 사용자" : boardPost.getAuthor().getName(),
                AttachmentResponse.from(boardPost.getAttachment()),
                boardPost.getCreatedAt(),
                boardPost.getUpdatedAt(),
                boardPost.isWrittenBy(currentUserId)
        );
    }

    public record AttachmentResponse(Long id, String fileName, String contentType, long size) {
        private static AttachmentResponse from(com.aivle.be.boardpost.entity.BoardPostAttachment attachment) {
            if (attachment == null) {
                return null;
            }
            return new AttachmentResponse(
                    attachment.getId(),
                    attachment.getFileName(),
                    attachment.getContentType(),
                    attachment.getFileSize()
            );
        }
    }
}
