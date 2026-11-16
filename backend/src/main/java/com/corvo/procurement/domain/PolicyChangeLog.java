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
@Table(name = "policy_change_log")
@Getter
@Setter
@NoArgsConstructor
public class PolicyChangeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "version_id")
    private Long versionId;
    private String operation;
    private String target;
    @Column(name = "before_json")
    private String beforeJson;
    @Column(name = "after_json")
    private String afterJson;
    private String actor;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
