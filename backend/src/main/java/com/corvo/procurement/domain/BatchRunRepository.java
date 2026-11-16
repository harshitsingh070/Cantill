package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BatchRunRepository extends JpaRepository<BatchRun, Long> {
    Optional<BatchRun> findByRunKey(String runKey);
}
