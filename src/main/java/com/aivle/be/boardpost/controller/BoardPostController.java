package com.aivle.be.boardpost.controller;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.boardpost.controller.request.BoardPostCreateRequest;
import com.aivle.be.boardpost.controller.request.BoardPostUpdateRequest;
import com.aivle.be.boardpost.controller.response.BoardPostResponse;
import com.aivle.be.boardpost.service.BoardPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Board Post", description = "자유게시판 게시글 관리 API")
@RestController
@RequestMapping("/api/board-posts")
@RequiredArgsConstructor
public class BoardPostController {

    private final BoardPostService boardPostService;
    private final AuthenticatedRequesterResolver requesterResolver;

    @Operation(summary = "게시글 목록 조회")
    @GetMapping
    public ResponseEntity<List<BoardPostResponse>> getAll(Authentication authentication) {
        return ResponseEntity.ok(boardPostService.getAll(requester(authentication)));
    }

    @Operation(summary = "게시글 상세 조회")
    @GetMapping("/{postId}")
    public ResponseEntity<BoardPostResponse> get(
            @PathVariable Long postId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(boardPostService.get(postId, requester(authentication)));
    }

    @Operation(summary = "게시글 작성")
    @PostMapping
    public ResponseEntity<BoardPostResponse> create(
            @Valid @RequestBody BoardPostCreateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardPostService.create(request, requester(authentication)));
    }

    @Operation(summary = "게시글 수정")
    @PatchMapping("/{postId}")
    public ResponseEntity<BoardPostResponse> update(
            @PathVariable Long postId,
            @Valid @RequestBody BoardPostUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                boardPostService.update(postId, request, requester(authentication))
        );
    }

    @Operation(summary = "게시글 삭제")
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long postId,
            Authentication authentication
    ) {
        boardPostService.delete(postId, requester(authentication));
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedRequester requester(Authentication authentication) {
        return requesterResolver.resolve(authentication);
    }
}
