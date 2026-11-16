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
@Table(name = "contract_consumption_runs")
@Getter
@Setter
@NoArgsConstructor
public class ContractConsumptionRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id")
    private String runId;
    @Column(name = "contract_id")
    private String contractId;
    @Column(name = "requirement_id")
    private Long requirementId;
    private BigDecimal amount;
}
