package com.corvo.procurement.engine.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record PolicySnapshot(
        Long id,
        String label,
        LocalDate effectiveFrom,
        LocalDateTime approvedAt,
        LocalDateTime activatedAt,
        String contentHash,
        List<PolicyRuleInfo> rules,
        List<PolicyParameterInfo> parameters,
        Map<String, PolicyFactInfo> facts
) {
}
