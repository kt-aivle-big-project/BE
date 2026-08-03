package com.aivle.be.optimization.client;

import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.web.client.RestClientException;

@Component
public class OptimizationClient {

    private final RestClient restClient;

    public OptimizationClient(
            @Value("${fastapi.base-url}") String fastApiBaseUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(fastApiBaseUrl)
                .build();
    }

    public OptimizationResponse optimize(OptimizationRequest request) {
        // TODO: FastAPI 팀 최종 엔드포인트 확정 후 URI 수정
        return restClient
                .post()
                .uri("/optimize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OptimizationResponse.class);
    }

    public ReoptimizationResponse reoptimize(
            ReoptimizationOptimizationRequest request
    ) {
        try {
            ReoptimizationResponse response = restClient.post()
                    .uri("/reoptimize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ReoptimizationResponse.class);

            if (response == null) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_AI_FAILED
                );
            }

            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_AI_FAILED,
                    exception
            );
        }
    }
}