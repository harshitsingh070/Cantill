package com.corvo.procurement.policy.amendment;

import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyRule;

import java.time.LocalDate;
import java.util.List;

public record PolicyDraft(
        LocalDate effectiveFrom,
        List<PolicyRule> rules,
        List<PolicyParameter> parameters
) {
}
