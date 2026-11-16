package com.corvo.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "policy_facts")
@Getter
@Setter
@NoArgsConstructor
public class PolicyFact {
    @Id
    @Column(name = "fact_key")
    private String factKey;
    @Column(name = "value_type")
    private String valueType;
    @Column(name = "resolver_key")
    private String resolverKey;
    @Column(name = "config_json")
    private String configJson;
    private String description;
    @Column(name = "is_available")
    private Boolean isAvailable;
}
