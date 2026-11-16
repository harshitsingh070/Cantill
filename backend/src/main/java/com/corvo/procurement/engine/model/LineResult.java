package com.corvo.procurement.engine.model;

import java.math.BigDecimal;

public record LineResult(
        Long prLineId,
        String prNo,
        Integer prLine,
        String resolvedItemCode,
        Long groupId,
        String poId,
        String decision,
        String route,
        String supplierId,
        BigDecimal unitPrice,
        BigDecimal groupValueInr,
        String suggestedSupplierIds,
        String policyVersion,
        String reason,
        BigDecimal matchScore,
        String matchMethod,
        String validationStatus
) {
}
