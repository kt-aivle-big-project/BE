package com.aivle.be.warehouse.dto;

/**
 * 지도 가져오기 결과.
 *
 * 무엇이 몇 개 만들어졌는지 알려준다.
 * 화면에서 "노드 159개, 랙 48개를 등록했습니다" 처럼 보여줄 수 있다.
 */
public record WarehouseImportResponse(
        Long warehouseId,
        String aiWarehouseId,
        String name,

        int nodeCount,
        int edgeCount,
        int rackCount,
        int chargingStationCount,
        int robotCount,

        /** 지도에 있었지만 저장하지 않은 노드 수 (작업 전용 자리 등) */
        int skippedNodeCount
) {}
