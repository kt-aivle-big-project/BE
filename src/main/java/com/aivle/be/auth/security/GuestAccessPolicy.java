package com.aivle.be.auth.security;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class GuestAccessPolicy {

    public static final long DEMO_WAREHOUSE_ID = 1L;
    public static final long DEMO_SCENARIO_ID = 101L;

    public void validateSimulationRunCreate(
            AuthenticatedRequester requester,
            Long warehouseId,
            Long scenarioId
    ) {
        if (!requester.isGuest()) {
            return;
        }
        requireDemoId(warehouseId, DEMO_WAREHOUSE_ID);
        requireDemoId(scenarioId, DEMO_SCENARIO_ID);
    }

    public void validateWarehouseRead(
            AuthenticatedRequester requester,
            Long warehouseId
    ) {
        if (requester.isGuest()) {
            requireDemoId(warehouseId, DEMO_WAREHOUSE_ID);
        }
    }

    public void validateScenarioRead(
            AuthenticatedRequester requester,
            Long scenarioId
    ) {
        if (requester.isGuest()) {
            requireDemoId(scenarioId, DEMO_SCENARIO_ID);
        }
    }

    public void requireUser(AuthenticatedRequester requester) {
        if (!requester.isUser()) {
            throw accessDenied();
        }
    }

    private void requireDemoId(Long actual, long expected) {
        if (actual == null || actual != expected) {
            throw accessDenied();
        }
    }

    private BusinessException accessDenied() {
        return new BusinessException(ErrorCode.ACCESS_DENIED);
    }
}
