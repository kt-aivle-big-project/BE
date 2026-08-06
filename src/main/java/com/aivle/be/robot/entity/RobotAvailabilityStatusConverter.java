package com.aivle.be.robot.entity;

import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import static com.aivle.be.robot.domain.RobotAvailabilityStatus.AVAILABLE;
import static com.aivle.be.robot.domain.RobotAvailabilityStatus.UNAVAILABLE;

@Converter
public class RobotAvailabilityStatusConverter
        implements AttributeConverter<RobotAvailabilityStatus, String> {

    @Override
    public String convertToDatabaseColumn(RobotAvailabilityStatus status) {
        if (status == null) {
            return null;
        }

        return switch (status) {
            case AVAILABLE -> "AVAILABLE";
            case UNAVAILABLE -> "UNAVAILABLE";
        };
    }

    @Override
    public RobotAvailabilityStatus convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }

        return switch (value) {
            case "AVAILABLE", "IDLE" -> AVAILABLE;
            case "UNAVAILABLE", "BUSY", "CHARGING" -> UNAVAILABLE;
            default -> throw new IllegalArgumentException(
                    "Unknown robot availability: " + value
            );
        };
    }
}