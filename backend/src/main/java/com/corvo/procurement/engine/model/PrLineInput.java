package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PrLineInput(
        Long id,
        String prNo,
        Integer prLine,
        LocalDateTime createdAt,
        String requester,
        String department,
        String plant,
        String itemCode,
        String description,
        BigDecimal quantity,
        String uom,
        BigDecimal estimatedUnitPrice,
        LocalDate needByDate,
        String urgency
) {
}
