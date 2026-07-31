package com.aivle.be.auth.security;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestAccessPolicyTest {

    private final GuestAccessPolicy policy = new GuestAccessPolicy();
    private final AuthenticatedRequester guest =
            AuthenticatedRequester.guest("a4d70ea4-9a96-4c75-8414-24a43114a962");

    @Test
    void guestCanUseOnlyDemoWarehouseAndScenario() {
        assertThatCode(() -> policy.validateSimulationRunCreate(guest, 1L, 101L))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateWarehouseRead(guest, 1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateScenarioRead(guest, 101L))
                .doesNotThrowAnyException();

        assertAccessDenied(() -> policy.validateSimulationRunCreate(guest, 2L, 101L));
        assertAccessDenied(() -> policy.validateSimulationRunCreate(guest, 1L, 2L));
        assertAccessDenied(() -> policy.validateSimulationRunCreate(guest, 1L, null));
        assertAccessDenied(() -> policy.validateWarehouseRead(guest, 2L));
        assertAccessDenied(() -> policy.validateScenarioRead(guest, 2L));
    }

    @Test
    void userKeepsExistingWarehouseAndScenarioAccess() {
        AuthenticatedRequester user = AuthenticatedRequester.user(10L);

        assertThatCode(() -> policy.validateSimulationRunCreate(user, 99L, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateWarehouseRead(user, 99L))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateScenarioRead(user, 99L))
                .doesNotThrowAnyException();
    }

    @Test
    void guestCannotUseUserOnlyOperations() {
        assertAccessDenied(() -> policy.requireUser(guest));
    }

    private void assertAccessDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    ErrorCode errorCode =
                            ((BusinessException) exception).getErrorCode();
                    assertThat(errorCode).isEqualTo(ErrorCode.ACCESS_DENIED);
                    assertThat(errorCode.getStatus().value()).isEqualTo(403);
                });
    }
}
