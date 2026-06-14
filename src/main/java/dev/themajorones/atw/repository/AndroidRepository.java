package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.themajorones.models.entity.Android;

public interface AndroidRepository extends JpaRepository<Android, Integer> {
}
