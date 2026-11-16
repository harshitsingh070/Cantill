package com.corvo.procurement.engine.model;

public record PolicyOutcome(
        String decision,
        String route,
        String supplierStrategy,
        Integer suggestionCount,
        boolean requiresCapexApproval
) {
}
