package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, Long> {
    List<PolicyRule> findByVersionIdOrderByPriorityAsc(Long versionId);

    Optional<PolicyRule> findByVersionIdAndRuleKey(Long versionId, String ruleKey);
}
