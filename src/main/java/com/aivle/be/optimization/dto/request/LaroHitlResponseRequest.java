package com.aivle.be.optimization.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record LaroHitlResponseRequest(
        @NotBlank
        String action,
        @JsonProperty("selected_option_id")
        @JsonAlias("selectedOptionId")
        String selectedOptionId,
        @JsonProperty("selected_entity_ids")
        @JsonAlias("selectedEntityIds")
        List<String> selectedEntityIds,
        @JsonProperty("resolution_value")
        @JsonAlias("resolutionValue")
        String resolutionValue,
        @JsonProperty("actor_id")
        @JsonAlias("actorId")
        String actorId,
        String comment
) {
    public LaroHitlResponseRequest {
        selectedEntityIds = selectedEntityIds == null
                ? List.of()
                : List.copyOf(selectedEntityIds);
        actorId = actorId == null || actorId.isBlank()
                ? "operator"
                : actorId;
    }
}
