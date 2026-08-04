package com.aivle.be.fulfillmentcommand.domain;

/** 자연어 정책 명령을 생성할 때 사용할 운영 목표. */
public enum CommandPolicyProfile {
    AUTO,
    BALANCED,
    BATTERY_SAVING,
    CONGESTION_AVOIDANCE,
    THROUGHPUT
}
