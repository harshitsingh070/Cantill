package com.corvo.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "contracts")
@Getter
@Setter
@NoArgsConstructor
public class Contract {
    @Id
    @Column(name = "contract_id")
    private String contractId;
    @Column(name = "supplier_id")
    private String supplierId;
    @Column(name = "supplier_name")
    private String supplierName;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "value_cap")
    private BigDecimal valueCap;
    @Column(name = "consumed_value")
    private BigDecimal consumedValue;
    private String source;
    private String status;
}
