package com.aivle.be.optimization.dto.request;

import com.aivle.be.optimization.domain.ReoptimizationReason;

import java.util.List;

public record ReoptimizationRequest(

        ReoptimizationReason reason,

        // 재최적화를 유발한 로봇 ID
        // 수동 요청이나 신규 작업 추가일 때는 null 가능
        Long triggerRobotId,

        // 장애물로 차단된 엣지 ID 목록
        // 장애물 사유가 아니면 빈 배열 또는 null 가능
        List<Long> blockedEdgeIds,

        // 관리자 메모 또는 재계산 사유 설명
        String description
) {
}