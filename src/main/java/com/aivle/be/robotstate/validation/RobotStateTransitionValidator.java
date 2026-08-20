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

    // 어떤 상태에서든 빠질 수 있는 종료/예외 상태
    private static final EnumSet<RobotStatus> TERMINAL_STATUSES =
            EnumSet.of(RobotStatus.ERROR, RobotStatus.OFFLINE);

    // 작업 수행 계열 (MOVING에서 진입 가능)
    private static final EnumSet<RobotStatus> WORKING_STATUSES = EnumSet.of(
            RobotStatus.WORKING,
            RobotStatus.PICKING,
            RobotStatus.PUTAWAY,
            RobotStatus.REPLENISH,
            RobotStatus.RELOCATION
    );

    private static final Map<RobotStatus, EnumSet<RobotStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(RobotStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(RobotStatus.IDLE,
                EnumSet.of(RobotStatus.ASSIGNED, RobotStatus.CHARGING, RobotStatus.ERROR, RobotStatus.OFFLINE));

        ALLOWED_TRANSITIONS.put(RobotStatus.ASSIGNED,
                EnumSet.of(RobotStatus.MOVING, RobotStatus.IDLE, RobotStatus.ERROR, RobotStatus.OFFLINE));

        // 이동 중 → 각 작업 유형으로 진입 가능
        EnumSet<RobotStatus> fromMoving = EnumSet.of(
                RobotStatus.IDLE, RobotStatus.CHARGING, RobotStatus.ERROR, RobotStatus.OFFLINE);
        fromMoving.addAll(WORKING_STATUSES);
        ALLOWED_TRANSITIONS.put(RobotStatus.MOVING, fromMoving);

        for (RobotStatus working : WORKING_STATUSES) {
            EnumSet<RobotStatus> next = EnumSet.of(
                    RobotStatus.MOVING, RobotStatus.IDLE, RobotStatus.ERROR, RobotStatus.OFFLINE);
            next.addAll(WORKING_STATUSES);
            next.remove(working);
            ALLOWED_TRANSITIONS.put(working, next);
        }

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

        if (TERMINAL_STATUSES.contains(nextStatus)) {
            return;
        }

        if (!ALLOWED_TRANSITIONS.getOrDefault(currentStatus, EnumSet.noneOf(RobotStatus.class))
                .contains(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_ROBOT_STATE_TRANSITION);
        }
    }
}
