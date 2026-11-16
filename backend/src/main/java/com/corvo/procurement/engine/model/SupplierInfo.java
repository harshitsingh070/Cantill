package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.util.List;

public record SupplierInfo(
        String supplierId,
        String name,
        String city,
        List<String> categories,
        String status,
        BigDecimal onTimePct,
        BigDecimal qualityRating
) {
    public boolean isActive() {
        return "Active".equalsIgnoreCase(status);
    }
}
