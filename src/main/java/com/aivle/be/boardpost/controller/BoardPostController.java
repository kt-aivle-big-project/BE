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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

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

    @Operation(summary = "게시글 첨부파일 업로드 또는 교체")
    @PostMapping(value = "/{postId}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BoardPostResponse> uploadAttachment(
            @PathVariable Long postId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                boardPostService.uploadAttachment(postId, file, requester(authentication))
        );
    }

    @Operation(summary = "게시글 첨부파일 다운로드")
    @GetMapping("/{postId}/attachment")
    public ResponseEntity<ByteArrayResource> downloadAttachment(@PathVariable Long postId) {
        BoardPostService.AttachmentDownload attachment = boardPostService.downloadAttachment(postId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(attachment.contentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.data().length)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(attachment.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(new ByteArrayResource(attachment.data()));
    }

    @Operation(summary = "게시글 첨부파일 삭제")
    @DeleteMapping("/{postId}/attachment")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable Long postId,
            Authentication authentication
    ) {
        boardPostService.deleteAttachment(postId, requester(authentication));
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedRequester requester(Authentication authentication) {
        return requesterResolver.resolve(authentication);
    }
}
