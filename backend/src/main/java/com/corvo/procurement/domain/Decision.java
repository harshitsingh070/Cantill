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

import java.math.BigDecimal;

@Entity
@Table(name = "decisions")
@Getter
@Setter
@NoArgsConstructor
public class Decision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id")
    private Long runId;
    @Column(name = "requirement_id")
    private Long requirementId;
    private String decision;
    private String route;
    @Column(name = "supplier_id")
    private String supplierId;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
    @Column(name = "group_value_inr")
    private BigDecimal groupValueInr;
    @Column(name = "suggested_supplier_ids")
    private String suggestedSupplierIds;
    @Column(name = "policy_version")
    private String policyVersion;
    private String clause;
    @Column(name = "source_reference")
    private String sourceReference;
    private String reason;
    @Column(name = "trace_json")
    private String traceJson;
}
