package com.aivle.be.fulfillmentcommand.domain;

/**
 * AI planning request에서 같은 구조화 작업을 어떤 표현으로 전달할지 결정한다.
 * 구조화 작업은 모든 모드에서 실행 원본으로 유지된다.
 */
public enum CommandExpressionMode {
    /** 자동 주기에서 표현 방식을 가중 무작위로 선택한다. */
    AUTO,
    /** 자연어 없이 구조화 작업만 전달한다. */
    STRUCTURED_ONLY,
    /** 구조화 작업에 자연어 운영 정책을 추가한다. */
    STRUCTURED_WITH_POLICY,
    /** 전체 작업을 자연어 명령으로도 표현하되 구조화 작업을 검증 원본으로 유지한다. */
    NATURAL_LANGUAGE
}
