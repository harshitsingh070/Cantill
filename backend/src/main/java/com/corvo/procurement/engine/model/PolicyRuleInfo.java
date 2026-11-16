package com.corvo.procurement.engine.model;

public record PolicyRuleInfo(
        String ruleKey,
        Integer priority,
        String clauseRef,
        String name,
        String scopeJson,
        String conditionJson,
        String outcomeJson,
        Boolean enabled
) {
}
