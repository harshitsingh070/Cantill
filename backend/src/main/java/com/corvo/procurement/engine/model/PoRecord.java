package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PoRecord(
        String poNumber,
        LocalDate poDate,
        String supplierId,
        String itemCode,
        BigDecimal quantity,
        String uom,
        BigDecimal unitPrice,
        String plant,
        Boolean deliveredOnTime
) {
}
