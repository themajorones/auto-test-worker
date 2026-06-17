package dev.themajorones.atw.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.themajorones.models.entity.Ollama;

public interface OllamaRepository extends JpaRepository<Ollama, Integer> {
}
