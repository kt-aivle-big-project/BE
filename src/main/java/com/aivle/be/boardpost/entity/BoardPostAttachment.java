package com.aivle.be.boardpost.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "board_post_attachments")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class BoardPostAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attachment_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_post_id", nullable = false, unique = true)
    private BoardPost boardPost;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static BoardPostAttachment create(
            BoardPost boardPost,
            String fileName,
            String contentType,
            long fileSize,
            String objectKey
    ) {
        BoardPostAttachment attachment = new BoardPostAttachment();
        attachment.boardPost = boardPost;
        attachment.update(fileName, contentType, fileSize, objectKey);
        attachment.createdAt = LocalDateTime.now();
        return attachment;
    }

    public void update(String fileName, String contentType, long fileSize, String objectKey) {
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.objectKey = objectKey;
    }
}
