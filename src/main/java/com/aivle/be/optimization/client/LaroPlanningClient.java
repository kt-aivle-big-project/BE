package com.aivle.be.optimization.client;

import com.aivle.be.optimization.dto.request.LaroNativePlanRequest;
import com.aivle.be.optimization.dto.request.LaroHitlResponseRequest;
import com.aivle.be.optimization.dto.response.LaroHitlResponse;
import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LaroPlanningClient {

    private final RestClient restClient;

    public LaroPlanningClient(
            RestClient.Builder restClientBuilder,
            @Value("${fastapi.base-url}") String fastApiBaseUrl
    ) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(fastApiBaseUrl)
                .build();
    }

    public LaroPlanResponse createPlan(
            String warehouseId,
            LaroNativePlanRequest request
    ) {
        return restClient.post()
                .uri(
                        "/api/v1/warehouses/{warehouseId}/missions/plan",
                        warehouseId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(LaroPlanResponse.class);
    }

    public LaroHitlResponse respondToHumanInteraction(
            String interactionId,
            LaroHitlResponseRequest request
    ) {
        return restClient.post()
                .uri("/hitl/{interactionId}/respond", interactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(LaroHitlResponse.class);
    }
}
