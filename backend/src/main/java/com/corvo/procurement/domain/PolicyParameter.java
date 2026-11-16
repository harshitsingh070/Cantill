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
@Table(name = "policy_parameters")
@Getter
@Setter
@NoArgsConstructor
public class PolicyParameter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "version_id")
    private Long versionId;
    @Column(name = "param_key")
    private String paramKey;
    @Column(name = "value_json")
    private String valueJson;
    @Column(name = "value_type")
    private String valueType;
    @Column(name = "scope_json")
    private String scopeJson;
}
