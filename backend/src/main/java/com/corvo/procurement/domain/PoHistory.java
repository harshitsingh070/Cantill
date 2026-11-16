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
@Table(name = "po_history")
@Getter
@Setter
@NoArgsConstructor
public class PoHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "po_number")
    private String poNumber;
    @Column(name = "po_date")
    private LocalDate poDate;
    @Column(name = "supplier_id")
    private String supplierId;
    @Column(name = "item_code")
    private String itemCode;
    private BigDecimal quantity;
    private String uom;
    @Column(name = "unit_price")
    private BigDecimal unitPrice;
    private String plant;
    @Column(name = "delivered_on_time")
    private Boolean deliveredOnTime;
}
