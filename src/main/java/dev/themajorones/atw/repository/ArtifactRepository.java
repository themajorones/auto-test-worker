package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.themajorones.models.entity.Artifact;

public interface ArtifactRepository extends JpaRepository<Artifact, Integer> {
}
