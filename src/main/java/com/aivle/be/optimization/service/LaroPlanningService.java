package com.aivle.be.optimization.service;

import com.aivle.be.optimization.client.LaroPlanningClient;
import com.aivle.be.optimization.dto.request.LaroNativePlanRequest;
import com.aivle.be.optimization.dto.request.LaroPlanRequest;
import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LaroPlanningService {

    private final LaroPlanningClient laroPlanningClient;

    public LaroPlanResponse createPlan(
            String warehouseId,
            LaroPlanRequest request
    ) {
        LaroNativePlanRequest nativeRequest =
                LaroNativePlanRequest.from(warehouseId, request);

        return laroPlanningClient.createPlan(
                warehouseId,
                nativeRequest
        );
    }
}
