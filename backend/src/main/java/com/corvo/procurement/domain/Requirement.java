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
import java.time.LocalDateTime;

@Entity
@Table(name = "requirements")
@Getter
@Setter
@NoArgsConstructor
public class Requirement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id")
    private Long runId;
    @Column(name = "requirement_date")
    private LocalDate requirementDate;
    @Column(name = "first_created_at")
    private LocalDateTime firstCreatedAt;
    @Column(name = "item_code")
    private String itemCode;
    private String plant;
    private String urgency;
    @Column(name = "contract_id")
    private String contractId;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
    private BigDecimal value;
    @Column(name = "valuation_source")
    private String valuationSource;
    @Column(name = "is_new_item")
    private Boolean isNewItem;
}
