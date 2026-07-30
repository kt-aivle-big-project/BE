package com.aivle.be.optimization.service;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import org.springframework.stereotype.Service;

@Service
public class ReplanningDecisionService {

    /**
     * 이벤트가 경로 재계획을 필요로 하는지 판단한다.
     *
     * @param reason 재최적화 요청 사유
     * @return 재계획이 필요하면 true, 룰 기반 처리가 가능하면 false
     */
    public boolean requiresReplanning(ReoptimizationReason reason) {
        return switch (reason) {
            // 단순 작업 완료 처리는 현재 경로에 영향을 주지 않는다고 판단
            case ROBOT_TASK_COMPLETED -> false;

            // 로봇·작업·경로에 영향을 주는 이벤트
            case ROBOT_FAILURE,
                 LOW_BATTERY,
                 OBSTACLE_DETECTED,
                 NEW_TASK_ADDED,
                 MANUAL_REQUEST -> true;
        };
    }
}