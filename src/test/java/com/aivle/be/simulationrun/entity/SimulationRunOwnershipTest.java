package com.aivle.be.simulationrun.entity;

import com.aivle.be.user.entity.User;
import com.aivle.be.warehouse.entity.Warehouse;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationRunOwnershipTest {

    @Test
    void userAndGuestOwnershipAreMutuallyExclusive() {
        SimulationRun run = SimulationRun.create(
                mock(Warehouse.class),
                LocalDateTime.now()
        );
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);

        run.assignUser(user);

        assertThat(run.getUser()).isSameAs(user);
        assertThat(run.getGuestSessionId()).isNull();
        assertThat(run.isOwnedByUser(7L)).isTrue();
        assertThat(run.isOwnedByGuest("guest-a")).isFalse();

        run.assignGuestSession("guest-a");

        assertThat(run.getUser()).isNull();
        assertThat(run.getGuestSessionId()).isEqualTo("guest-a");
        assertThat(run.isOwnedByUser(7L)).isFalse();
        assertThat(run.isOwnedByGuest("guest-a")).isTrue();
    }
}
