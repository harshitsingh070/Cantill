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

import java.time.LocalDateTime;

@Entity
@Table(name = "sourcing_events")
@Getter
@Setter
@NoArgsConstructor
public class SourcingEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String route;
    @Column(name = "requirement_id")
    private Long requirementId;
    @Column(name = "suggested_supplier_ids")
    private String suggestedSupplierIds;
    @Column(name = "approval_status")
    private String approvalStatus;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
