package com.corvo.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "policy_rules")
@Getter
@Setter
@NoArgsConstructor
public class PolicyRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "version_id")
    private Long versionId;
    @Column(name = "rule_key")
    private String ruleKey;
    private Integer priority;
    @Column(name = "clause_ref")
    private String clauseRef;
    private String name;
    @Column(name = "scope_json")
    private String scopeJson;
    @Column(name = "condition_json")
    private String conditionJson;
    @Column(name = "outcome_json")
    private String outcomeJson;
    private Boolean enabled;
}
