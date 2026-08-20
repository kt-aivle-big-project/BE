package com.aivle.be.warehousenode.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum NodeType {

    // 이동 통로
    ROUTE,

    // 통로 ↔ 충전 구역 분기점
    ROUTE_CHARGE_JUNCTION,

    // 랙(선반) 보관 위치
    RACK_STORAGE,

    RACK_ACCESS,

    INBOUND_HANDOFF_ACCESS,

    OUTBOUND_STATION_ACCESS,

    EMPTY_TOTE_BUFFER_ACCESS,

    // 입고구
    INBOUND,

    // 출고구
    OUTBOUND,

    // 충전 슬롯
    CHARGING_SLOT,

    PARKING_SLOT;

    @JsonCreator
    public static NodeType from(String value) {
        if (value == null) {
            return null;
        }
        return NodeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String value() {
        return name();
    }

    public boolean isStorage() {
        return this == RACK_STORAGE;
    }

    public boolean isServiceAccess() {
        return this == RACK_ACCESS
                || this == INBOUND_HANDOFF_ACCESS
                || this == OUTBOUND_STATION_ACCESS
                || this == EMPTY_TOTE_BUFFER_ACCESS;
    }
}
