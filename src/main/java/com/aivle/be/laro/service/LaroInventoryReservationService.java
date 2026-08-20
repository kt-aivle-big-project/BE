package com.aivle.be.laro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LaroInventoryReservationService {

    private static final Logger log =
            LoggerFactory.getLogger(LaroInventoryReservationService.class);

    private final JdbcTemplate jdbcTemplate;

    public LaroInventoryReservationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int releaseActiveForRun(Long simulationRunId) {
        if (simulationRunId == null) {
            return 0;
        }
        return updateQuietly(
                """
                UPDATE laro_ext.inventory_reservation
                   SET status = 'RELEASED', updated_at = now()
                 WHERE simulation_run_id = ?
                   AND status = 'ACTIVE'
                """,
                simulationRunId
        );
    }

    @Transactional
    public int releaseActiveForPlan(Long simulationRunId, String planId) {
        if (simulationRunId == null || planId == null || planId.isBlank()) {
            return 0;
        }
        return updateQuietly(
                """
                UPDATE laro_ext.inventory_reservation
                   SET status = 'RELEASED', updated_at = now()
                 WHERE simulation_run_id = ?
                   AND plan_id = ?
                   AND status = 'ACTIVE'
                """,
                simulationRunId,
                planId
        );
    }

    @Transactional
    public void failAndReleasePlan(Long simulationRunId, String planId) {
        if (simulationRunId == null || planId == null || planId.isBlank()) {
            return;
        }
        updateQuietly(
                """
                UPDATE laro_ext.simulation_plan
                   SET status = 'FAILED'
                 WHERE simulation_run_id = ?
                   AND plan_id = ?
                """,
                simulationRunId,
                planId
        );
        releaseActiveForPlan(simulationRunId, planId);
    }

    @Transactional
    public int releaseSupersededPlan(Long simulationRunId, String activatedPlanId) {
        if (simulationRunId == null || activatedPlanId == null || activatedPlanId.isBlank()) {
            return 0;
        }
        return updateQuietly(
                """
                UPDATE laro_ext.inventory_reservation reservation
                   SET status = 'RELEASED', updated_at = now()
                 WHERE reservation.simulation_run_id = ?
                   AND reservation.status = 'ACTIVE'
                   AND reservation.plan_id = (
                       SELECT plan.base_plan_id
                         FROM laro_ext.simulation_plan plan
                        WHERE plan.plan_id = ?
                          AND plan.simulation_run_id = ?
                   )
                """,
                simulationRunId,
                activatedPlanId,
                simulationRunId
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileInactiveRunsOnStartup() {
        int released = updateQuietly(
                """
                UPDATE laro_ext.inventory_reservation reservation
                   SET status = 'RELEASED', updated_at = now()
                  FROM public.simulation_runs run
                 WHERE run.simulation_run_id = reservation.simulation_run_id
                   AND reservation.status = 'ACTIVE'
                   AND run.status IN ('CREATED', 'COMPLETED', 'STOPPED', 'FAILED')
                """
        );
        if (released > 0) {
            log.info("Released {} stale LARO inventory reservations on startup", released);
        }
    }

    private int updateQuietly(String sql, Object... args) {
        try {
            return jdbcTemplate.update(sql, args);
        } catch (DataAccessException exception) {
            log.debug("LARO reservation schema is unavailable: {}", exception.getMessage());
            return 0;
        }
    }
}
