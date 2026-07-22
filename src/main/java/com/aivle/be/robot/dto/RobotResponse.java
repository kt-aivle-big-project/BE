package com.aivle.be.robot.dto;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RobotResponse {

    private Long id;
    private Long robotSpecId;
    private Long warehouseId;
    private Long nodeId;
    private Integer battery;
    private RobotAvailabilityStatus status;

    public static RobotResponse from(Robot robot) {
        return RobotResponse.builder()
                .id(robot.getId())
                .robotSpecId(robot.getRobotSpec().getId())
                .warehouseId(robot.getWarehouse().getId())
                .nodeId(robot.getNodeId())
                .battery(robot.getBattery())
                .status(robot.getStatus())
                .build();
    }
}
