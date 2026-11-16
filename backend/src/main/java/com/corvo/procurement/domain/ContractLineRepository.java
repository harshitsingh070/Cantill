package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContractLineRepository extends JpaRepository<ContractLine, Long> {
    List<ContractLine> findByContractId(String contractId);

    List<ContractLine> findByItemCode(String itemCode);
}
