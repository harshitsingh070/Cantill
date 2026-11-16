package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PoHistoryRepository extends JpaRepository<PoHistory, Long> {
    List<PoHistory> findByItemCodeOrderByPoDateDescPoNumberDesc(String itemCode);

    List<PoHistory> findByItemCodeAndPoDateBetweenOrderByPoDateDescPoNumberDesc(String itemCode, LocalDate from, LocalDate to);
}
