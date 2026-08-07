package com.aivle.be.boardpost.controller.response;

import com.aivle.be.boardpost.entity.BoardPost;

import java.time.LocalDateTime;

public record BoardPostResponse(
        Long id,
        String title,
        String content,
        Long authorId,
        String authorName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean mine
) {
    public static BoardPostResponse from(BoardPost boardPost, Long currentUserId) {
        return new BoardPostResponse(
                boardPost.getId(),
                boardPost.getTitle(),
                boardPost.getContent(),
                boardPost.getAuthor().getId(),
                boardPost.getAuthor().getName(),
                boardPost.getCreatedAt(),
                boardPost.getUpdatedAt(),
                boardPost.isWrittenBy(currentUserId)
        );
    }
}
