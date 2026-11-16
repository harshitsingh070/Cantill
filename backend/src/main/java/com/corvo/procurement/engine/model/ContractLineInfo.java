package com.corvo.procurement.engine.model;

import java.math.BigDecimal;

public record ContractLineInfo(
        String itemCode,
        String description,
        String uom,
        BigDecimal rate
) {
}
