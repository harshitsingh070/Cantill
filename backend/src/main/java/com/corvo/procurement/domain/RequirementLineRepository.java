package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementLineRepository extends JpaRepository<RequirementLine, Long> {
    List<RequirementLine> findByRequirementId(Long requirementId);
}
