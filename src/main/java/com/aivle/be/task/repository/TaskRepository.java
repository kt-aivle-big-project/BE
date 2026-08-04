package com.aivle.be.task.repository;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // 이 로봇이 지금 ASSIGNED/IN_PROGRESS 상태의 다른 Task를 이미 갖고 있는지 확인 (Redis 없이 우리 DB만으로 판단)
    boolean existsByRobot_IdAndStatusIn(Long robotId, Collection<TaskStatus> statuses);

    List<Task> findAllBySimulationRun_IdOrderByRequestedAtAsc(Long simulationRunId);

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

    boolean existsBySimulationRun_IdAndStatus(Long simulationRunId, TaskStatus status);

    // 재생 엔진에서 로봇이 수행 중인 작업을 찾을 때 사용
    List<Task> findAllByRobot_IdAndStatusIn(Long robotId, Collection<TaskStatus> statuses);

    @Modifying(flushAutomatically = true)
    @Query("update Task task set task.robot = null where task.robot.id = :robotId")
    void clearRobotReference(@Param("robotId") Long robotId);

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
}
