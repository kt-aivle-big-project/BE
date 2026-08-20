package com.aivle.be.scenario.domain;

/**
 * 시나리오 검증 상태. 시나리오 목록 화면의 상태 필터와 배지에 대응한다.
 */
public enum ScenarioStatus {

    /** 만들어만 두고 아직 확인하지 않음 */
    DRAFT,

    /** 검증 중 */
    VALIDATING,

    /** 검증 완료 */
    VALIDATED
}
