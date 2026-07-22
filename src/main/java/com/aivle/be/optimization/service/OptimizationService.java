package com.aivle.be.optimization.service;

import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.entity.OptimizationResult;
import com.aivle.be.optimization.entity.RobotRouteResult;
import com.aivle.be.optimization.repository.OptimizationResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OptimizationService {

    private final OptimizationClient optimizationClient;
    private final OptimizationResultRepository optimizationResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Transactional
    public OptimizationResponse optimize(OptimizationRequest request) {
        OptimizationResponse response =
                optimizationClient.optimize(request);

        OptimizationResult result = OptimizationResult.create(
                response.requestId(),
                request.warehouseId(),
                response.status()
        );

        for (OptimizationResponse.RobotRoute route : response.routes()) {
            String nodePathJson = convertNodePathToJson(route);

            RobotRouteResult routeResult = RobotRouteResult.create(
                    route.robotId(),
                    nodePathJson,
                    route.totalDistance(),
                    route.estimatedTime()
            );

            result.addRoute(routeResult);
        }

        optimizationResultRepository.save(result);

        return response;
    }

    private String convertNodePathToJson(
            OptimizationResponse.RobotRoute route
    ) {
        try {
            return objectMapper.writeValueAsString(route.nodePath());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "경로 노드 목록을 JSON으로 변환하지 못했습니다.",
                    e
            );
        }
    }
}