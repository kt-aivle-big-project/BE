package com.aivle.be.scenario.domain;

public enum ScenarioStatus {

    /** 만들어만 두고 아직 확인하지 않음 */
    DRAFT,

    /** 검증 중 */
    VALIDATING,

    /** 검증 완료 */
    VALIDATED
}
