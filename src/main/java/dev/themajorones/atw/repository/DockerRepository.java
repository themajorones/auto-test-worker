package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dev.themajorones.models.entity.Docker;

@Repository
public interface DockerRepository extends JpaRepository<Docker, Integer> {
}
