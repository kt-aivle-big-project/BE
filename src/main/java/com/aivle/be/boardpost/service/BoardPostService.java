package com.aivle.be.boardpost.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.boardpost.controller.request.BoardPostCreateRequest;
import com.aivle.be.boardpost.controller.request.BoardPostUpdateRequest;
import com.aivle.be.boardpost.controller.response.BoardPostResponse;
import com.aivle.be.boardpost.entity.BoardPost;
import com.aivle.be.boardpost.entity.BoardPostAttachment;
import com.aivle.be.boardpost.repository.BoardPostRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class BoardPostService {

    private static final long MAX_ATTACHMENT_SIZE = 2L * 1024 * 1024;

    private final BoardPostRepository boardPostRepository;
    private final UserRepository userRepository;
    private final GuestAccessPolicy guestAccessPolicy;
    private final BoardPostFileStorage fileStorage;

    public List<BoardPostResponse> getAll(AuthenticatedRequester requester) {
        Long currentUserId = currentUserId(requester);
        return boardPostRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(boardPost -> BoardPostResponse.from(boardPost, currentUserId))
                .toList();
    }

    public BoardPostResponse get(Long postId, AuthenticatedRequester requester) {
        return BoardPostResponse.from(findById(postId), currentUserId(requester));
    }

    @Transactional
    public BoardPostResponse create(
            BoardPostCreateRequest request,
            AuthenticatedRequester requester
    ) {
        guestAccessPolicy.requireUser(requester);
        User author = userRepository.findById(requester.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        BoardPost boardPost = BoardPost.create(
                author,
                request.title().trim(),
                request.content().trim()
        );
        return BoardPostResponse.from(
                boardPostRepository.save(boardPost),
                requester.userId()
        );
    }

    @Transactional
    public BoardPostResponse update(
            Long postId,
            BoardPostUpdateRequest request,
            AuthenticatedRequester requester
    ) {
        guestAccessPolicy.requireUser(requester);
        BoardPost boardPost = findById(postId);
        validateAuthor(boardPost, requester.userId());
        boardPost.update(request.title().trim(), request.content().trim());
        return BoardPostResponse.from(boardPost, requester.userId());
    }

    @Transactional
    public void delete(Long postId, AuthenticatedRequester requester) {
        guestAccessPolicy.requireUser(requester);
        BoardPost boardPost = findById(postId);
        validateAuthor(boardPost, requester.userId());
        String objectKey = boardPost.getAttachment() == null
                ? null
                : boardPost.getAttachment().getObjectKey();
        boardPostRepository.delete(boardPost);
        deleteAfterCommit(objectKey);
    }

    @Transactional
    public BoardPostResponse uploadAttachment(
            Long postId,
            MultipartFile file,
            AuthenticatedRequester requester
    ) {
        guestAccessPolicy.requireUser(requester);
        BoardPost boardPost = findById(postId);
        validateAuthor(boardPost, requester.userId());
        validateFile(file);

        try {
            String fileName = sanitizeFileName(file.getOriginalFilename());
            String contentType = file.getContentType() == null
                    ? "application/octet-stream"
                    : file.getContentType();
            byte[] data = file.getBytes();
            String oldObjectKey = boardPost.getAttachment() == null
                    ? null
                    : boardPost.getAttachment().getObjectKey();
            String newObjectKey = fileStorage.upload(postId, fileName, contentType, data);
            cleanNewObjectOnRollback(newObjectKey);
            boardPost.replaceAttachment(
                    fileName,
                    contentType,
                    data.length,
                    newObjectKey
            );
            boardPostRepository.flush();
            deleteAfterCommit(oldObjectKey);
            return BoardPostResponse.from(boardPost, requester.userId());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, exception);
        }
    }

    public AttachmentDownload downloadAttachment(Long postId) {
        BoardPostAttachment attachment = findById(postId).getAttachment();
        if (attachment == null) {
            throw new BusinessException(ErrorCode.BOARD_POST_ATTACHMENT_NOT_FOUND);
        }
        return new AttachmentDownload(
                attachment.getFileName(),
                attachment.getContentType(),
                fileStorage.download(attachment.getObjectKey())
        );
    }

    @Transactional
    public void deleteAttachment(Long postId, AuthenticatedRequester requester) {
        guestAccessPolicy.requireUser(requester);
        BoardPost boardPost = findById(postId);
        validateAuthor(boardPost, requester.userId());
        BoardPostAttachment attachment = boardPost.getAttachment();
        if (attachment == null) {
            throw new BusinessException(ErrorCode.BOARD_POST_ATTACHMENT_NOT_FOUND);
        }
        String objectKey = attachment.getObjectKey();
        boardPost.removeAttachment();
        deleteAfterCommit(objectKey);
    }

    private BoardPost findById(Long postId) {
        return boardPostRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOARD_POST_NOT_FOUND));
    }

    private void validateAuthor(BoardPost boardPost, Long userId) {
        if (!boardPost.isWrittenBy(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private Long currentUserId(AuthenticatedRequester requester) {
        return requester.isUser() ? requester.userId() : null;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BOARD_POST_ATTACHMENT_EMPTY);
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            throw new BusinessException(ErrorCode.BOARD_POST_ATTACHMENT_TOO_LARGE);
        }
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "attachment";
        }
        String normalized = originalFileName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isBlank()) {
            return "attachment";
        }
        return fileName.length() <= 255 ? fileName : fileName.substring(fileName.length() - 255);
    }

    private void cleanNewObjectOnRollback(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    safeDelete(objectKey);
                }
            }
        });
    }

    private void deleteAfterCommit(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDelete(objectKey);
            }
        });
    }

    private void safeDelete(String objectKey) {
        try {
            fileStorage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.error("S3 첨부파일 삭제 실패: objectKey={}", objectKey, exception);
        }
    }

    public record AttachmentDownload(String fileName, String contentType, byte[] data) {
    }
}
