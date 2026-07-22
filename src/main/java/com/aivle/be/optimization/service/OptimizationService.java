package com.aivle.be.optimization.service;

import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.OptimizationResultResponse;
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
    @Transactional(readOnly = true)
    public OptimizationResultResponse getResult(Long resultId) {
        OptimizationResult result = optimizationResultRepository.findById(resultId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "최적화 결과를 찾을 수 없습니다. resultId=" + resultId
                        )
                );

        return OptimizationResultResponse.from(result, objectMapper);
    }

    @Transactional(readOnly = true)
    public OptimizationResultResponse getResultByRequestId(String requestId) {
        OptimizationResult result = optimizationResultRepository
                .findByRequestId(requestId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "최적화 결과를 찾을 수 없습니다. requestId=" + requestId
                        )
                );

        return OptimizationResultResponse.from(result, objectMapper);
    }
}