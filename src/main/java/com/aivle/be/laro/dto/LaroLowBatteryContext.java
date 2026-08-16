package com.aivle.be.laro.dto;

/**
 * Trusted simulator state captured after the affected robot reaches a safe
 * node.  This is intentionally separate from the optional public runtime
 * snapshot because the Spring playback engine is authoritative for this
 * system-generated LOW_BATTERY replan.
 */
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
