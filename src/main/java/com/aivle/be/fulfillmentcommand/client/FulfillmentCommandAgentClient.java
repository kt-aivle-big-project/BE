package com.aivle.be.fulfillmentcommand.client;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandSelection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FulfillmentCommandAgentClient {

    private final RestClient restClient;

    public FulfillmentCommandAgentClient(
            @Value("${fastapi.base-url}") String fastApiBaseUrl
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(fastApiBaseUrl)
                .build();
    }

    public FulfillmentCommandGenerateResponse generate(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request,
            FulfillmentCommandSelection selection
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "mode", request.mode());
        put(body, "inboundCount", request.inboundCount());
        put(body, "outboundCount", request.outboundCount());
        put(body, "inboundProductCodes", request.inboundProductCodes());
        put(body, "outboundProductCodes", request.outboundProductCodes());
        put(body, "priority", request.priority());
        put(body, "releaseIntervalMs", request.releaseIntervalMs());
        put(body, "commandExpressionMode", request.commandExpressionMode());
        put(body, "policyProfile", request.policyProfile());
        put(body, "mixStructuredWithPolicy", request.mixStructuredWithPolicy());
        put(body, "mixNaturalLanguage", request.mixNaturalLanguage());
        body.put("inboundCount", selection.inboundCount());
        body.put("outboundCount", selection.outboundCount());
        body.put("commandExpressionMode", selection.commandExpressionMode().name());
        body.put("selectionSeed", selection.selectionSeed());
        body.put("preselectedOperations", selection.operations());

        FulfillmentCommandGenerateResponse response = restClient.post()
                .uri(
                        "/api/v1/simulation-runs/{id}/fulfillment-commands/generate",
                        simulationRunId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FulfillmentCommandGenerateResponse.class);
        if (response == null) {
            throw new IllegalStateException(
                    "LARO fulfillment-command Agent returned an empty response"
            );
        }
        return response;
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }
}
