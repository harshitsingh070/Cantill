package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.util.List;

public record DecisionRecord(
        long requirementId,
        String decision,
        String route,
        String supplierId,
        BigDecimal unitPrice,
        BigDecimal groupValueInr,
        List<String> suggestedSupplierIds,
        String policyVersion,
        String clause,
        String sourceReference,
        String reason,
        String traceJson,
        String poId,
        String contractId,
        boolean requiresCapexApproval,
        List<PreferredSupplier> preferredSuppliers
) {
}
