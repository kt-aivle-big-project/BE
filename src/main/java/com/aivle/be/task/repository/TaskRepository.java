package com.aivle.be.task.repository;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    boolean existsByRobot_IdAndStatusIn(
            Long robotId,
            Collection<TaskStatus> statuses
    );

    List<Task> findAllBySimulationRun_IdOrderByRequestedAtAsc(
            Long simulationRunId
    );

    Optional<Task> findBySimulationRun_IdAndExternalOperationId(
            Long simulationRunId,
            String externalOperationId
    );

    List<Task> findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
            Long simulationRunId,
            Collection<TaskStatus> statuses
    );

    long countBySimulationRun_Id(Long simulationRunId);

    boolean existsBySimulationRun_IdAndStatusNotIn(
            Long simulationRunId,
            Collection<TaskStatus> statuses
    );

    boolean existsBySimulationRun_IdAndStatus(
            Long simulationRunId,
            TaskStatus status
    );

    List<Task> findAllByRobot_IdAndStatusIn(
            Long robotId,
            Collection<TaskStatus> statuses
    );

    @Modifying(flushAutomatically = true)
    @Query("update Task task set task.robot = null where task.robot.id = :robotId")
    void clearRobotReference(@Param("robotId") Long robotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select task
            from Task task
            where task.simulationRun.id = :simulationRunId
            order by task.id
            """)
    List<Task> findAllBySimulationRunIdForUpdateOrderById(
            @Param("simulationRunId") Long simulationRunId
    );

    /* =========================================================
       운영 대시보드 집계
       요청 시각(requestedAt) 기준으로 기간을 자른다.
    ========================================================= */

    List<Task> findAllByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAtDesc(
            LocalDateTime from,
            LocalDateTime to
    );

    @Query("""
            select count(task)
            from Task task
            where task.warehouse.id = :warehouseId
              and task.status in :taskStatuses
              and (
                    task.simulationRun is null
                    or task.simulationRun.status in :runStatuses
              )
              and (
                    task.startNode.id in :nodeIds
                    or task.endNode.id in :nodeIds
              )
            """)
    long countOperationalReferences(
            @Param("warehouseId") Long warehouseId,
            @Param("nodeIds") Collection<Long> nodeIds,
            @Param("taskStatuses") Collection<TaskStatus> taskStatuses,
            @Param("runStatuses") Collection<com.aivle.be.simulationrun.domain.SimulationRunStatus> runStatuses
    );

    List<Task> findAllByWarehouse_IdAndRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAtDesc(
            Long warehouseId,
            LocalDateTime from,
            LocalDateTime to
    );
}