package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PrLineRepository extends JpaRepository<PrLine, Long> {
    List<PrLine> findByBatchIdOrderByCreatedAtAscPrNoAscPrLineAsc(Long batchId);
}
