package com.aivle.be.event.controller;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Event", description = "로봇/창고 이벤트(충돌 위험, 경로 차단 등) 기록 및 조회 API")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "이벤트 생성 (장애물/차단 계열은 경로 겹침 판단까지 함께 수행)")
    @ApiResponse(responseCode = "200", description = "생성 성공")
    @ApiResponse(responseCode = "404", description = "창고·로봇·작업을 찾을 수 없음")
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@RequestBody EventCreateRequest request) {
        return ResponseEntity.ok(eventService.createEvent(request));
    }

    @Operation(summary = "이벤트 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "이벤트를 찾을 수 없음")
    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @Operation(summary = "이벤트 전체 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @Operation(summary = "이벤트 해결 처리")
    @ApiResponse(responseCode = "200", description = "해결 처리 성공")
    @ApiResponse(responseCode = "404", description = "이벤트를 찾을 수 없음")
    @PatchMapping("/{eventId}/resolve")
    public ResponseEntity<EventResponse> resolveEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.resolveEvent(eventId));
    }
}