package com.corvo.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
public class Supplier {
    @Id
    @Column(name = "supplier_id")
    private String supplierId;
    private String name;
    private String city;
    private String categories;
    private String status;
    @Column(name = "on_time_pct")
    private BigDecimal onTimePct;
    @Column(name = "quality_rating")
    private BigDecimal qualityRating;

    public boolean isActive() {
        return "Active".equalsIgnoreCase(status);
    }
}
