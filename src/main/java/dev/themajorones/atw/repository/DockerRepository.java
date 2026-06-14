package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.themajorones.models.entity.Docker;

public interface DockerRepository extends JpaRepository<Docker, Integer> {
}
