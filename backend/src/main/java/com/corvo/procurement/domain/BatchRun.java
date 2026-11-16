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
@Table(name = "batch_runs")
@Getter
@Setter
@NoArgsConstructor
public class BatchRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_key")
    private String runKey;
    private String filename;
    @Column(name = "input_hash")
    private String inputHash;
    @Column(name = "policy_version_label")
    private String policyVersionLabel;
    @Column(name = "uploaded_by")
    private String uploadedBy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "result_json")
    private String resultJson;
}
