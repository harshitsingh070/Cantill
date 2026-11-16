package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RequirementRecord(
        long id,
        String itemCode,
        String plant,
        String urgency,
        LocalDate requirementDate,
        LocalDateTime firstCreatedAt,
        LocalDate needByDate,
        boolean newItem,
        List<PrLineInput> lines,
        BigDecimal value,
        String valuationSource,
        String usedContractId,
        BigDecimal unitPrice
) {
}
