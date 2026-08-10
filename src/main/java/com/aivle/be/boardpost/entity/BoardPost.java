package com.aivle.be.boardpost.entity;

import com.aivle.be.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "board_posts")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class BoardPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "board_post_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 5000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToOne(mappedBy = "boardPost", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private BoardPostAttachment attachment;

    public static BoardPost create(User author, String title, String content) {
        LocalDateTime now = LocalDateTime.now();
        BoardPost boardPost = new BoardPost();
        boardPost.author = author;
        boardPost.title = title;
        boardPost.content = content;
        boardPost.createdAt = now;
        boardPost.updatedAt = now;
        return boardPost;
    }

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isWrittenBy(Long userId) {
        return userId != null && author != null && userId.equals(author.getId());
    }

    public void replaceAttachment(String fileName, String contentType, long fileSize, String objectKey) {
        if (attachment == null) {
            attachment = BoardPostAttachment.create(this, fileName, contentType, fileSize, objectKey);
            return;
        }
        attachment.update(fileName, contentType, fileSize, objectKey);
    }

    public void removeAttachment() {
        attachment = null;
    }
}
