package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionRepository extends JpaRepository<Decision, Long> {
    List<Decision> findByRunId(Long runId);

    Decision findByRequirementId(Long requirementId);

    List<Decision> findByPoId(String poId);

    List<Decision> findByRunIdAndPoId(Long runId, String poId);
}
