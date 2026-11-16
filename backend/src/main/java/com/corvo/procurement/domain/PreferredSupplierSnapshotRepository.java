package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PreferredSupplierSnapshotRepository extends JpaRepository<PreferredSupplierSnapshot, Long> {
    List<PreferredSupplierSnapshot> findByItemCodeAndAsOfDateOrderByRankAsc(String itemCode, LocalDate asOfDate);
}
