package com.corvo.procurement.engine.model;

import java.util.List;

public record EngineResult(
        List<LineResult> lines,
        List<RequirementRecord> requirements,
        List<DecisionRecord> decisions,
        List<PoGroup> poGroups
) {
}
