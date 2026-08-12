package com.aivle.be.laro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Browser response to one pending LARO human-review interaction. */
public record LaroHumanReviewRequest(
        @NotBlank String action,
        String selectedOptionId,
        List<String> selectedEntityIds,
        String resolutionValue,
        @Size(max = 2000) String comment,
        @PositiveOrZero long executionVersion
) {
    public List<String> effectiveSelectedEntityIds() {
        return selectedEntityIds == null ? List.of() : List.copyOf(selectedEntityIds);
    }
}
