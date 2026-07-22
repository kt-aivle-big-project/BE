package com.aivle.be.optimization.service;

import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OptimizationService {

    private final OptimizationClient optimizationClient;

    public OptimizationResponse optimize(OptimizationRequest request) {
        // TODO: 경로 결과 저장 기능 구현 후 응답 결과를 DB에 저장
        return optimizationClient.optimize(request);
    }
}