package com.aivle.be.event.controller.request;

import com.aivle.be.event.entity.EventType;

// robotId, taskId는 이벤트 종류에 따라 없을 수도 있어서 nullable
// nodeId: 장애물/차단 발생 위치. COLLISION_RISK, PATH_BLOCKED일 때 넣어주면 경로 겹침 판단이 자동 실행됨. 그 외엔 null 가능
public record EventCreateRequest(
        Long warehouseId,
        Long robotId,
        Long taskId,
        EventType eventType,
        String description,
        Long nodeId
) {
}