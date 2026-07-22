package com.aivle.be.optimization.dto.response;

import com.aivle.be.optimization.entity.OptimizationResult;
import com.aivle.be.optimization.entity.RobotRouteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

public record OptimizationResultResponse(
        Long id,
        String requestId,
        Long warehouseId,
        String status,
        LocalDateTime createdAt,
        List<RouteResponse> routes
) {

    public static OptimizationResultResponse from(
            OptimizationResult result,
            ObjectMapper objectMapper
    ) {
        List<RouteResponse> routes = result.getRoutes().stream()
                .map(route -> RouteResponse.from(route, objectMapper))
                .toList();

        return new OptimizationResultResponse(
                result.getId(),
                result.getRequestId(),
                result.getWarehouseId(),
                result.getStatus(),
                result.getCreatedAt(),
                routes
        );
    }

    public record RouteResponse(
            Long id,
            Long robotId,
            List<Long> nodePath,
            Double totalDistance,
            Double estimatedTime
    ) {

        public static RouteResponse from(
                RobotRouteResult route,
                ObjectMapper objectMapper
        ) {
            try {
                List<Long> nodePath = objectMapper.readValue(
                        route.getNodePath(),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, Long.class)
                );

                return new RouteResponse(
                        route.getId(),
                        route.getRobotId(),
                        nodePath,
                        route.getTotalDistance(),
                        route.getEstimatedTime()
                );
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "저장된 경로를 읽지 못했습니다.",
                        e
                );
            }
        }
    }
}