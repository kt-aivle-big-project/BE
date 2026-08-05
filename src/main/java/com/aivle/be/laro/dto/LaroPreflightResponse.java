package com.aivle.be.laro.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LaroPreflightResponse(
        String status,
        boolean ready,
        @JsonProperty("simulation_run_id") Long simulationRunId,
        @JsonProperty("warehouse_id") String warehouseId,
        @JsonProperty("warehouse_numeric_id") Long warehouseNumericId,
        Map<String, String> sources,
        Map<String, Integer> counts,
        @JsonProperty("runtime_mode") String runtimeMode,
        List<String> problems
) {}
