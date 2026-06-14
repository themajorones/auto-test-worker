package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.themajorones.models.entity.TaskLog;

public interface TaskLogRepository extends JpaRepository<TaskLog, Integer> {
}
