package com.aivle.be.laro.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LaroTaskIdTest {

    @Test
    void optimizerPhaseIdsShareOneLogicalTaskBase() {
        assertThat(LaroTaskId.base("TASK-001_PICK")).isEqualTo("TASK-001");
        assertThat(LaroTaskId.base("TASK-001_DROP")).isEqualTo("TASK-001");
        assertThat(LaroTaskId.base("TASK-001_RETURN")).isEqualTo("TASK-001");
        assertThat(LaroTaskId.base("TASK-001_EMPTY_TOTE")).isEqualTo("TASK-001");
        assertThat(LaroTaskId.matches("TASK-001_PICK", "TASK-001_DROP")).isTrue();
    }

    @Test
    void ordinaryIdsAreNotChanged() {
        assertThat(LaroTaskId.base("OUT-20260803-001")).isEqualTo("OUT-20260803-001");
        assertThat(LaroTaskId.base(null)).isNull();
    }
}
