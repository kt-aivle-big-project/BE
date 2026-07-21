package com.aivle.be.chargingstation.controller;

import com.aivle.be.chargingstation.dto.request.ChargingStationRequest;
import com.aivle.be.chargingstation.dto.response.ChargingStationResponse;
import com.aivle.be.chargingstation.service.ChargingStationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/charging-stations")
public class ChargingStationController {

    private final ChargingStationService chargingStationService;

    @PostMapping
    public ResponseEntity<ChargingStationResponse> createChargingStation(
            @RequestBody ChargingStationRequest request
    ) {
        ChargingStationResponse response =
                chargingStationService.createChargingStation(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{chargingStationId}")
    public ResponseEntity<ChargingStationResponse> getChargingStation(
            @PathVariable Long chargingStationId
    ) {
        return ResponseEntity.ok(
                chargingStationService.getChargingStation(chargingStationId)
        );
    }

    @GetMapping
    public ResponseEntity<List<ChargingStationResponse>>
    getChargingStations() {
        return ResponseEntity.ok(
                chargingStationService.getChargingStations()
        );
    }

    @PatchMapping("/{chargingStationId}")
    public ResponseEntity<ChargingStationResponse> updateChargingStation(
            @PathVariable Long chargingStationId,
            @RequestBody ChargingStationRequest request
    ) {
        return ResponseEntity.ok(
                chargingStationService.updateChargingStation(
                        chargingStationId,
                        request
                )
        );
    }

    @DeleteMapping("/{chargingStationId}")
    public ResponseEntity<Void> deleteChargingStation(
            @PathVariable Long chargingStationId
    ) {
        chargingStationService.deleteChargingStation(chargingStationId);

        return ResponseEntity.noContent().build();
    }
}