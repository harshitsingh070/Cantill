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
@Table(name = "pr_lines")
@Getter
@Setter
@NoArgsConstructor
public class PrLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_id")
    private Long batchId;
    @Column(name = "pr_no")
    private String prNo;
    @Column(name = "pr_line")
    private Integer prLine;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    private String requester;
    private String department;
    private String plant;
    @Column(name = "raw_item_code")
    private String rawItemCode;
    @Column(name = "raw_description")
    private String rawDescription;
    private BigDecimal quantity;
    @Column(name = "raw_uom")
    private String rawUom;
    @Column(name = "estimated_unit_price")
    private BigDecimal estimatedUnitPrice;
    @Column(name = "need_by_date")
    private LocalDate needByDate;
    private String urgency;
    @Column(name = "resolved_item_code")
    private String resolvedItemCode;
    @Column(name = "match_score")
    private BigDecimal matchScore;
    @Column(name = "match_method")
    private String matchMethod;
    @Column(name = "validation_status")
    private String validationStatus;
}
