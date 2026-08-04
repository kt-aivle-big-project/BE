package com.aivle.be.simulationrun.domain;

public enum SimulationRunStatus {

    // 생성됨 (대기)
    CREATED,

    // 실행 중
    RUNNING,

    // 일시정지
    PAUSED,

    QUIESCING,

    // 재계획 중 (AI 재최적화 요청 ~ 결과 반영)
    REPLANNING,

    PENDING_ACTIVATION,

    // 정상 완료
    COMPLETED,

    // 수동 중지
    STOPPED,

    // 실패 종료
    FAILED
}
