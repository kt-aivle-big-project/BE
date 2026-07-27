package com.aivle.be.robot.controller;

import com.aivle.be.robot.dto.RobotCreateRequest;
import com.aivle.be.robot.dto.RobotResponse;
import com.aivle.be.robot.dto.RobotUpdateRequest;
import com.aivle.be.robot.service.RobotService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/robots")
@RequiredArgsConstructor
public class RobotController {

    private final RobotService robotService;

    @PostMapping
    public ResponseEntity<RobotResponse> createRobot(
            @Valid @RequestBody RobotCreateRequest request
    ) {
        RobotResponse response = robotService.createRobot(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{robotId}")
    public ResponseEntity<RobotResponse> getRobot(
            @PathVariable Long robotId
    ) {
        return ResponseEntity.ok(
                robotService.getRobot(robotId)
        );
    }

    @GetMapping
    public ResponseEntity<List<RobotResponse>> getRobots(
            @RequestParam(required = false) Long warehouseId
    ) {
        if (warehouseId != null) {
            return ResponseEntity.ok(
                    robotService.getRobotsByWarehouse(warehouseId)
            );
        }

        return ResponseEntity.ok(
                robotService.getRobots()
        );
    }

    @PatchMapping("/{robotId}")
    public ResponseEntity<RobotResponse> updateRobot(
            @PathVariable Long robotId,
            @Valid @RequestBody RobotUpdateRequest request
    ) {
        return ResponseEntity.ok(
                robotService.updateRobot(robotId, request)
        );
    }

    @DeleteMapping("/{robotId}")
    public ResponseEntity<Void> deleteRobot(
            @PathVariable Long robotId
    ) {
        robotService.deleteRobot(robotId);

        return ResponseEntity.noContent().build();
    }
}
