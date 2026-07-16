package com.aivle.be.event.controller;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@RequestBody EventCreateRequest request) {
        return ResponseEntity.ok(eventService.createEvent(request));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @PatchMapping("/{eventId}/resolve")
    public ResponseEntity<EventResponse> resolveEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.resolveEvent(eventId));
    }
}