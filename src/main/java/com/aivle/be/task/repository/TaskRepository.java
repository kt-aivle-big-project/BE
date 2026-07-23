package com.aivle.be.task.repository;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // 이 로봇이 지금 ASSIGNED/IN_PROGRESS 상태의 다른 Task를 이미 갖고 있는지 확인 (Redis 없이 우리 DB만으로 판단)
    boolean existsByRobot_IdAndStatusIn(Long robotId, Collection<TaskStatus> statuses);

    List<Task> findAllBySimulationRun_IdOrderByRequestedAtAsc(Long simulationRunId);

    long countBySimulationRun_Id(Long simulationRunId);

    boolean existsBySimulationRun_IdAndStatusNotIn(
            Long simulationRunId,
            Collection<TaskStatus> statuses
    );

    boolean existsBySimulationRun_IdAndStatus(Long simulationRunId, TaskStatus status);

    @Modifying(flushAutomatically = true)
    @Query("update Task task set task.robot = null where task.robot.id = :robotId")
    void clearRobotReference(@Param("robotId") Long robotId);
}
