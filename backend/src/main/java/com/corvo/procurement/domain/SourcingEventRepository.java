package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourcingEventRepository extends JpaRepository<SourcingEvent, Long> {
    List<SourcingEvent> findByRequirementId(Long requirementId);

    List<SourcingEvent> findByRequirementIdIn(List<Long> requirementIds);
}
