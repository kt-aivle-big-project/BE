package com.aivle.be.fulfillmentcommand.service;

import com.aivle.be.fulfillmentcommand.client.FulfillmentCommandAgentClient;
import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FulfillmentCommandGenerationService {

    private final FulfillmentCommandAgentClient agentClient;
    private final FulfillmentCommandRandomSelector randomSelector;

    public FulfillmentCommandGenerateResponse generate(Long simulationRunId) {
        return generate(simulationRunId, FulfillmentCommandGenerateRequest.automatic());
    }

    public FulfillmentCommandGenerateResponse generate(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request
    ) {
        FulfillmentCommandGenerateRequest effectiveRequest = request == null
                ? FulfillmentCommandGenerateRequest.automatic()
                : request;
        FulfillmentCommandSelection selection = randomSelector.select(
                simulationRunId,
                effectiveRequest
        );
        return agentClient.generate(simulationRunId, effectiveRequest, selection);
    }
}
