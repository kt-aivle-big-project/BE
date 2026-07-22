package com.aivle.be.robotspec.controller;

import com.aivle.be.robotspec.dto.RobotSpecRequest;
import com.aivle.be.robotspec.dto.RobotSpecResponse;
import com.aivle.be.robotspec.service.RobotSpecService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Robot Spec", description = "시뮬레이션 로봇 사양 조회 API")
@RestController
@RequestMapping("/api/robot-specs")
@RequiredArgsConstructor
public class RobotSpecController {

    private final RobotSpecService robotSpecService;

    @Operation(summary = "로봇 사양 등록")
    @PostMapping
    public ResponseEntity<RobotSpecResponse> create(
            @Valid @RequestBody RobotSpecRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(robotSpecService.create(request));
    }

    @Operation(summary = "로봇 사양 단건 조회")
    @GetMapping("/{robotSpecId}")
    public ResponseEntity<RobotSpecResponse> get(@PathVariable Long robotSpecId) {
        return ResponseEntity.ok(robotSpecService.get(robotSpecId));
    }

    @Operation(summary = "로봇 사양 목록 조회")
    @GetMapping
    public ResponseEntity<List<RobotSpecResponse>> getAll() {
        return ResponseEntity.ok(robotSpecService.getAll());
    }

    @Operation(summary = "로봇 사양 수정")
    @PatchMapping("/{robotSpecId}")
    public ResponseEntity<RobotSpecResponse> update(
            @PathVariable Long robotSpecId,
            @Valid @RequestBody RobotSpecRequest request
    ) {
        return ResponseEntity.ok(robotSpecService.update(robotSpecId, request));
    }

    @Operation(summary = "로봇 사양 삭제")
    @DeleteMapping("/{robotSpecId}")
    public ResponseEntity<Void> delete(@PathVariable Long robotSpecId) {
        robotSpecService.delete(robotSpecId);
        return ResponseEntity.noContent().build();
    }
}
