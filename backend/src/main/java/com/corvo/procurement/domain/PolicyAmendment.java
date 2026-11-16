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
@Table(name = "policy_amendments")
@Getter
@Setter
@NoArgsConstructor
public class PolicyAmendment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String source;
    @Column(name = "raw_text")
    private String rawText;
    @Column(name = "document_ref")
    private String documentRef;
    @Column(name = "extracted_json")
    private String extractedJson;
    @Column(name = "operations_json")
    private String operationsJson;
    @Column(name = "diff_json")
    private String diffJson;
    private String status;
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
    @Column(name = "applied_version_id")
    private Long appliedVersionId;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
