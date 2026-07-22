package com.aivle.be.task.repository;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // 이 로봇이 지금 ASSIGNED/IN_PROGRESS 상태의 다른 Task를 이미 갖고 있는지 확인 (Redis 없이 우리 DB만으로 판단)
    boolean existsByRobot_IdAndStatusIn(Long robotId, Collection<TaskStatus> statuses);
}