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
import java.time.LocalDate;

@Entity
@Table(name = "preferred_supplier_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class PreferredSupplierSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "item_code")
    private String itemCode;
    @Column(name = "as_of_date")
    private LocalDate asOfDate;
    @Column(name = "policy_version")
    private String policyVersion;
    private Integer rank;
    @Column(name = "supplier_id")
    private String supplierId;
    private BigDecimal score;
    @Column(name = "score_components")
    private String scoreComponents;
    @Column(name = "reference_price")
    private BigDecimal referencePrice;
    @Column(name = "has_contract")
    private Boolean hasContract;
    @Column(name = "po_count")
    private Integer poCount;
    @Column(name = "recency_bucket")
    private String recencyBucket;
}
