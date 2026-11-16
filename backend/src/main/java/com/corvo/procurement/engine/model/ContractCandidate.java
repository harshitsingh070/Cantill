package com.corvo.procurement.engine.model;

import java.math.BigDecimal;

public record ContractCandidate(
        ContractInfo contract,
        ContractLineInfo line,
        BigDecimal prospectiveValue,
        boolean usable
) {
}
