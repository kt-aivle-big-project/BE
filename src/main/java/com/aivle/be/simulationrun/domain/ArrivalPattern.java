package com.aivle.be.simulationrun.domain;

/**
 * 입고/출고 발생 패턴.
 * 프론트 입고·출고 설정의 "발생 패턴" 드롭다운과 대응한다.
 */
public enum ArrivalPattern {

    // 균등: 전체 기간에 고르게 분산
    UNIFORM,

    // 랜덤: 무작위 시점에 발생
    RANDOM,

    // 집중: 특정 시간대에 몰림
    PEAK
}
