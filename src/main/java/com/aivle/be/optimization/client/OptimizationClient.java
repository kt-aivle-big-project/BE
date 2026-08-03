package com.aivle.be.optimization.client;

import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;

public interface OptimizationClient {

    OptimizationResponse optimize(OptimizationRequest request);

    ReoptimizationResponse reoptimize(
            ReoptimizationOptimizationRequest request
    );
}
