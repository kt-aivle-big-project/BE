package com.aivle.be.robot.entity;

import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobotAvailabilityStatusConverterTest {

    private final RobotAvailabilityStatusConverter converter =
            new RobotAvailabilityStatusConverter();

    @Test
    void convertsAvailableToIdleForDatabase() {
        assertThat(converter.convertToDatabaseColumn(
                RobotAvailabilityStatus.AVAILABLE
        )).isEqualTo("IDLE");
    }

    @Test
    void convertsUnavailableToBusyForDatabase() {
        assertThat(converter.convertToDatabaseColumn(
                RobotAvailabilityStatus.UNAVAILABLE
        )).isEqualTo("BUSY");
    }

    @Test
    void convertsNullToNullForDatabase() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertsIdleToAvailableForEntity() {
        assertThat(converter.convertToEntityAttribute("IDLE"))
                .isEqualTo(RobotAvailabilityStatus.AVAILABLE);
    }

    @Test
    void convertsBusyAndChargingToUnavailableForEntity() {
        assertThat(converter.convertToEntityAttribute("BUSY"))
                .isEqualTo(RobotAvailabilityStatus.UNAVAILABLE);
        assertThat(converter.convertToEntityAttribute("CHARGING"))
                .isEqualTo(RobotAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void rejectsUnknownDatabaseValue() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown robot availability: UNKNOWN");
    }
}
