package com.aivle.be.auth.security;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class GuestAccessPolicy {

    public void validateSimulationRunCreate(
            AuthenticatedRequester requester,
            Long warehouseId,
            Long scenarioId
    ) {
        // Guests may use all provided demo warehouses and scenarios.
        // Existence and warehouse-scenario matching are validated by the service.
    }

    public void validateWarehouseRead(
            AuthenticatedRequester requester,
            Long warehouseId
    ) {
        // Guests may read all provided demo warehouses.
    }

    public void validateScenarioRead(
            AuthenticatedRequester requester,
            Long scenarioId
    ) {
        // Guests may read all provided demo scenarios.
    }

    public void requireUser(AuthenticatedRequester requester) {
        if (!requester.isUser()) {
            throw accessDenied();
        }
    }

    public void requireGuest(AuthenticatedRequester requester) {
        if (!requester.isGuest()) {
            throw accessDenied();
        }
    }

    private BusinessException accessDenied() {
        return new BusinessException(ErrorCode.ACCESS_DENIED);
    }
}
