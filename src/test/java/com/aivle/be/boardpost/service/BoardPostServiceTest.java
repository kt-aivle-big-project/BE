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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardPostServiceTest {

    @Mock
    private BoardPostRepository boardPostRepository;

    @Mock
    private UserRepository userRepository;

    @Spy
    private GuestAccessPolicy guestAccessPolicy = new GuestAccessPolicy();

    @InjectMocks
    private BoardPostService boardPostService;

    private User author;
    private User otherUser;

    @BeforeEach
    void setUp() {
        author = user(7L, "작성자");
        otherUser = user(8L, "다른 사용자");
    }

    @Test
    void returnsPostsInRepositoryOrderAndCalculatesMineForUser() {
        BoardPost othersPost = post(2L, otherUser, "두 번째 글");
        BoardPost minePost = post(1L, author, "첫 번째 글");
        when(boardPostRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(othersPost, minePost));

        List<BoardPostResponse> responses = boardPostService.getAll(
                AuthenticatedRequester.user(author.getId())
        );

        assertThat(responses).extracting(BoardPostResponse::id)
                .containsExactly(2L, 1L);
        assertThat(responses).extracting(BoardPostResponse::mine)
                .containsExactly(false, true);
    }

    @Test
    void guestAlwaysReceivesMineFalse() {
        when(boardPostRepository.findById(1L))
                .thenReturn(Optional.of(post(1L, author, "게시글")));

        BoardPostResponse response = boardPostService.get(
                1L,
                AuthenticatedRequester.guest("guest-session")
        );

        assertThat(response.mine()).isFalse();
        assertThat(response.authorId()).isEqualTo(author.getId());
        assertThat(response.authorName()).isEqualTo(author.getName());
    }

    @Test
    void createsPostWithAuthenticatedUserInsteadOfRequestBodyAuthor() {
        when(userRepository.findById(author.getId())).thenReturn(Optional.of(author));
        when(boardPostRepository.save(any(BoardPost.class))).thenAnswer(invocation -> {
            BoardPost boardPost = invocation.getArgument(0);
            ReflectionTestUtils.setField(boardPost, "id", 10L);
            return boardPost;
        });

        BoardPostResponse response = boardPostService.create(
                new BoardPostCreateRequest("  제목  ", "  내용  "),
                AuthenticatedRequester.user(author.getId())
        );

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.authorId()).isEqualTo(author.getId());
        assertThat(response.title()).isEqualTo("제목");
        assertThat(response.content()).isEqualTo("내용");
        assertThat(response.mine()).isTrue();
    }

    @Test
    void guestCannotCreateUpdateOrDeletePost() {
        AuthenticatedRequester guest = AuthenticatedRequester.guest("guest-session");

        assertAccessDenied(() -> boardPostService.create(
                new BoardPostCreateRequest("제목", "내용"), guest));
        assertAccessDenied(() -> boardPostService.update(
                1L, new BoardPostUpdateRequest("제목", "내용"), guest));
        assertAccessDenied(() -> boardPostService.delete(1L, guest));

        verifyNoInteractions(boardPostRepository, userRepository);
    }

    @Test
    void nonAuthorCannotUpdateOrDeletePost() {
        BoardPost boardPost = post(1L, author, "원래 제목");
        when(boardPostRepository.findById(1L)).thenReturn(Optional.of(boardPost));
        AuthenticatedRequester requester = AuthenticatedRequester.user(otherUser.getId());

        assertAccessDenied(() -> boardPostService.update(
                1L, new BoardPostUpdateRequest("수정 제목", "수정 내용"), requester));
        assertAccessDenied(() -> boardPostService.delete(1L, requester));

        assertThat(boardPost.getTitle()).isEqualTo("원래 제목");
        verify(boardPostRepository, never()).delete(any(BoardPost.class));
    }

    @Test
    void authorCanUpdateAndDeletePost() {
        BoardPost boardPost = post(1L, author, "원래 제목");
        when(boardPostRepository.findById(1L)).thenReturn(Optional.of(boardPost));
        AuthenticatedRequester requester = AuthenticatedRequester.user(author.getId());

        BoardPostResponse response = boardPostService.update(
                1L,
                new BoardPostUpdateRequest("  수정 제목  ", "  수정 내용  "),
                requester
        );
        boardPostService.delete(1L, requester);

        assertThat(response.title()).isEqualTo("수정 제목");
        assertThat(response.content()).isEqualTo("수정 내용");
        assertThat(response.mine()).isTrue();
        verify(boardPostRepository).delete(boardPost);
    }

    @Test
    void missingPostRaisesBoardPostNotFound() {
        when(boardPostRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> boardPostService.get(
                99L,
                AuthenticatedRequester.user(author.getId())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.BOARD_POST_NOT_FOUND));
    }

    private User user(Long id, String name) {
        User user = new User(id + "@example.com", name, "password-hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private BoardPost post(Long id, User user, String title) {
        BoardPost boardPost = BoardPost.create(user, title, "내용");
        ReflectionTestUtils.setField(boardPost, "id", id);
        return boardPost;
    }

    private void assertAccessDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.ACCESS_DENIED));
    }
}
