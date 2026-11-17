package com.corvo.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pos")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {
    @Id
    @Column(name = "po_id")
    private String poId;
    @Column(name = "supplier_id")
    private String supplierId;
    private String plant;
    private String route;
    @Column(name = "created_from_requirement")
    private Long createdFromRequirement;
    @Column(name = "first_requirement_at")
    private LocalDateTime firstRequirementAt;
    @Column(name = "approval_status")
    private String approvalStatus;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
}
