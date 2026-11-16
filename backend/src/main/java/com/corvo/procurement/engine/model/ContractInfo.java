package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ContractInfo(
        String contractId,
        String supplierId,
        String supplierName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal valueCap,
        BigDecimal consumedValue,
        String source,
        List<ContractLineInfo> lines
) {
    public ContractLineInfo lineFor(String itemCode) {
        return lines.stream()
                .filter(l -> l.itemCode().equals(itemCode))
                .findFirst()
                .orElse(null);
    }
}
