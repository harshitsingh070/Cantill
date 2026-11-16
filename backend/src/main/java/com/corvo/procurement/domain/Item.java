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
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
public class Item {
    @Id
    @Column(name = "item_code")
    private String itemCode;
    private String description;
    private String uom;
    private String category;
    @Column(name = "item_type")
    private String itemType;
    @Column(name = "standard_price")
    private BigDecimal standardPrice;
}
