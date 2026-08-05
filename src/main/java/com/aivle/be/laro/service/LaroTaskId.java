package com.aivle.be.laro.service;

import java.util.List;
import java.util.Objects;

/** Normalizes optimizer phase IDs to the logical task ID shared with BE. */
public final class LaroTaskId {

    private static final List<String> PHASE_SUFFIXES = List.of(
            "_EMPTY_TOTE",
            "_RETURN",
            "_DROP",
            "_PICK"
    );

    private LaroTaskId() {
    }

    public static String base(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return taskId;
        }
        for (String suffix : PHASE_SUFFIXES) {
            if (taskId.endsWith(suffix)) {
                return taskId.substring(0, taskId.length() - suffix.length());
            }
        }
        return taskId;
    }

    public static boolean matches(String left, String right) {
        return Objects.equals(base(left), base(right));
    }
}
