package com.corvo.procurement.engine.model;

import java.math.BigDecimal;

public record RequirementFacts(
        String itemCode,
        String itemType,
        String category,
        String plant,
        BigDecimal valueInr,
        String urgency,
        BigDecimal quantity,
        String uom,
        Integer needByOffsetDays,
        boolean hasApplicableContract,
        boolean hasContractWithCeiling,
        boolean hasActiveHistory,
        boolean isNewItem
) {
}
