package com.aivle.be.boardpost.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.boardpost.controller.request.BoardPostCreateRequest;
import com.aivle.be.boardpost.controller.request.BoardPostUpdateRequest;
import com.aivle.be.boardpost.controller.response.BoardPostResponse;
import com.aivle.be.boardpost.entity.BoardPost;
import com.aivle.be.boardpost.repository.BoardPostRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardPostService {

    private final BoardPostRepository boardPostRepository;
    private final UserRepository userRepository;
    private final GuestAccessPolicy guestAccessPolicy;

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
        boardPostRepository.delete(boardPost);
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
}
