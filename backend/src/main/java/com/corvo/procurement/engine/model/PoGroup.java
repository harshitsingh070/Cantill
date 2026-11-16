package com.corvo.procurement.engine.model;

import java.time.LocalDateTime;
import java.util.List;

public record PoGroup(
        String poId,
        String supplierId,
        String plant,
        String route,
        LocalDateTime firstRequirementAt,
        List<Long> requirementIds
) {
}
