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
@Table(name = "pr_batches")
@Getter
@Setter
@NoArgsConstructor
public class PrBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "run_id")
    private Long runId;
    private String filename;
    @Column(name = "uploaded_by")
    private String uploadedBy;
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
    @Column(name = "input_hash")
    private String inputHash;
}
