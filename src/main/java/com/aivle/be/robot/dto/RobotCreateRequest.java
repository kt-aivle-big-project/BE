package com.aivle.be.robot.dto;

import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RobotCreateRequest {

    private Long robotSpecId;
    private Long warehouseId;
    private Long nodeId;
    private Integer battery;
    private RobotAvailabilityStatus status;
}
