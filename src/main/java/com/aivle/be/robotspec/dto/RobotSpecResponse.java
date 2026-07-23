package com.aivle.be.robotspec.dto;

import com.aivle.be.robotspec.entity.RobotSpec;

public record RobotSpecResponse(
        Long id,
        String robotCode,
        String taskCode,
        Double baseBatteryRate,
        Double workBatteryRate,
        Double failureRate
) {
    public static RobotSpecResponse from(RobotSpec spec) {
        return new RobotSpecResponse(
                spec.getId(),
                spec.getRobotCode(),
                spec.getTaskCode(),
                spec.getBaseBatteryRate(),
                spec.getWorkBatteryRate(),
                spec.getFailureRate()
        );
    }
}
