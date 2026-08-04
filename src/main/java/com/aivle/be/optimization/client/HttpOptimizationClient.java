package com.aivle.be.optimization.client;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Profile("!mock-ai")
public class HttpOptimizationClient implements OptimizationClient {

    private final RestClient restClient;

    public HttpOptimizationClient(
            @Value("${fastapi.base-url}") String fastApiBaseUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(fastApiBaseUrl)
                .build();
    }

    @Override
    public OptimizationResponse optimize(OptimizationRequest request) {
        return restClient
                .post()
                .uri("/optimize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OptimizationResponse.class);
    }

    @Override
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
