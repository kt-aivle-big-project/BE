package com.aivle.be.optimization.domain;

public enum ReoptimizationReason {

    // 로봇이 맡은 작업을 예상보다 먼저 완료
    ROBOT_TASK_COMPLETED,

    // 로봇 고장 또는 운행 불가능
    ROBOT_FAILURE,

    // 배터리 부족으로 작업 수행 곤란
    LOW_BATTERY,

    // 장애물 또는 통로 차단 감지
    OBSTACLE_DETECTED,

    // 새로운 작업이 추가됨
    NEW_TASK_ADDED,

    // 관리자가 직접 재최적화를 요청
    MANUAL_REQUEST
}