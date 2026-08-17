package com.aivle.be.robotstate.domain;

/**
 * 로봇 실시간 운행 상태.
 * PICKING / PUTAWAY / REPLENISH / RELOCATION은 WORKING의 세부 작업 유형으로,
 * 프론트에서 로봇 아이콘을 구분해 표시하기 위해 사용한다.
 */
public enum RobotStatus {

    // 대기
    IDLE,

    // 작업 배정됨 (아직 이동 전)
    ASSIGNED,

    // 충돌 회피·통행 예약 등 계획된 대기
    WAITING,

    // 이동 중
    MOVING,

    // 배터리 부족으로 충전소로 복귀 중
    RETURNING_TO_CHARGE,

    // 작업 수행 중 (세부 유형 미지정)
    WORKING,

    // 피킹 (출고 집품)
    PICKING,

    // 적재 (입고 후 보관)
    PUTAWAY,

    // 보충
    REPLENISH,

    // 재배치
    RELOCATION,

    // 충전 중
    CHARGING,

    // 재계획을 위한 안전 정지
    PAUSED,

    // 오류/고장
    ERROR,

    // 오프라인
    OFFLINE;

    /**
     * 작업 수행 계열 상태인지 여부.
     */
    public boolean isWorking() {
        return this == WORKING
                || this == PICKING
                || this == PUTAWAY
                || this == REPLENISH
                || this == RELOCATION;
    }
}
