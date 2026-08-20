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
    }

    public void validateWarehouseRead(
            AuthenticatedRequester requester,
            Long warehouseId
    ) {
    }

    public void validateScenarioRead(
            AuthenticatedRequester requester,
            Long scenarioId
    ) {
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
