package com.aivle.be.laro.dto;

public record LaroLowBatteryContext(
        Long robotId,
        int batteryLevel,
        int chargingThreshold,
        Long currentNodeId,
        String currentNodeCode,
        Long currentTaskId,
        boolean carryingLoad,
        long stoppedAtSimTimeMs
) {}
