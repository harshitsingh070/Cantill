package com.corvo.procurement.engine.model;

import java.math.BigDecimal;
import java.util.Map;

public record PreferredSupplier(
        int rank,
        String supplierId,
        BigDecimal score,
        Map<String, Object> scoreComponents,
        BigDecimal referencePrice,
        String referenceSource,
        boolean hasContract,
        int poCount,
        String recencyBucket
) {
}
