package com.corvo.procurement.engine.model;

import java.math.BigDecimal;

public record ItemInfo(
        String itemCode,
        String description,
        String uom,
        String category,
        String itemType,
        BigDecimal standardPrice
) {
    public boolean isCapex() {
        return "Capex".equalsIgnoreCase(itemType);
    }
}
