package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionLineRepository extends JpaRepository<DecisionLine, Long> {
    List<DecisionLine> findByDecisionId(Long decisionId);
}
