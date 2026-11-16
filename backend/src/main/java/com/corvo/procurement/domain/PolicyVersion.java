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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_versions")
@Getter
@Setter
@NoArgsConstructor
public class PolicyVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "parent_version_id")
    private Long parentVersionId;
    private String label;
    @Column(name = "document_ref")
    private String documentRef;
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;
    private String status;
    private String source;
    @Column(name = "content_hash")
    private String contentHash;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    private String notes;
}
