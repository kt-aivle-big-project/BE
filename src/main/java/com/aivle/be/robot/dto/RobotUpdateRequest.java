package com.aivle.be.robot.dto;

import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Getter
@Setter
@NoArgsConstructor
public class RobotUpdateRequest {

    private Long robotSpecId;
    private Long warehouseId;
    private Long nodeId;
    @NotNull
    @Min(0)
    @Max(100)
    private Integer battery;
    private RobotAvailabilityStatus status;
}
