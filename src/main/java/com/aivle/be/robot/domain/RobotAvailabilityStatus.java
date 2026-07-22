package com.aivle.be.robot.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum RobotAvailabilityStatus {
    AVAILABLE,
    UNAVAILABLE;

    @JsonCreator
    public static RobotAvailabilityStatus from(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "AVAILABLE", "IDLE" -> AVAILABLE;
            case "UNAVAILABLE", "BUSY", "CHARGING" -> UNAVAILABLE;
            default -> throw new IllegalArgumentException("Unknown robot availability: " + value);
        };
    }

    @JsonValue
    public String value() {
        return name();
    }
}
