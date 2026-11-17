package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {
    List<Requirement> findByRunIdOrderByRequirementDateAscFirstCreatedAtAsc(Long runId);

    List<Requirement> findByRunId(Long runId);
}
