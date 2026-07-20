package com.aivle.be.robotstate.validation;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robotstate.domain.RobotStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

@Component
public class RobotStateTransitionValidator {

    private static final Map<RobotStatus, EnumSet<RobotStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(RobotStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(RobotStatus.IDLE,
                EnumSet.of(RobotStatus.ASSIGNED, RobotStatus.CHARGING, RobotStatus.ERROR, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.ASSIGNED,
                EnumSet.of(RobotStatus.MOVING, RobotStatus.IDLE, RobotStatus.ERROR, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.MOVING,
                EnumSet.of(RobotStatus.WORKING, RobotStatus.IDLE, RobotStatus.CHARGING,
                        RobotStatus.ERROR, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.WORKING,
                EnumSet.of(RobotStatus.MOVING, RobotStatus.IDLE, RobotStatus.ERROR, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.CHARGING,
                EnumSet.of(RobotStatus.IDLE, RobotStatus.ERROR, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.ERROR,
                EnumSet.of(RobotStatus.IDLE, RobotStatus.OFFLINE));
        ALLOWED_TRANSITIONS.put(RobotStatus.OFFLINE,
                EnumSet.of(RobotStatus.IDLE));
    }

    public void validate(RobotStatus currentStatus, RobotStatus nextStatus) {
        if (currentStatus == nextStatus) {
            return;
        }

        if (!ALLOWED_TRANSITIONS.getOrDefault(currentStatus, EnumSet.noneOf(RobotStatus.class))
                .contains(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_ROBOT_STATE_TRANSITION);
        }
    }
}
