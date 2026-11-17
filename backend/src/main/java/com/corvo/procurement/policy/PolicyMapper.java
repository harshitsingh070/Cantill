package com.corvo.procurement.policy;

import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyRule;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.engine.model.PolicyFactInfo;
import com.corvo.procurement.engine.model.PolicyParameterInfo;
import com.corvo.procurement.engine.model.PolicyRuleInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PolicyMapper {
    private PolicyMapper() {
    }

    public static PolicySnapshot snapshot(PolicyVersion version,
                                          List<PolicyRule> rules,
                                          List<PolicyParameter> parameters,
                                          List<PolicyFact> facts) {
        Map<String, PolicyFactInfo> factMap = new LinkedHashMap<>();
        for (PolicyFact fact : facts) {
            factMap.put(fact.getFactKey(), new PolicyFactInfo(
                    fact.getFactKey(),
                    fact.getValueType(),
                    fact.getResolverKey(),
                    fact.getConfigJson()
            ));
        }
        List<PolicyRuleInfo> ruleInfos = rules.stream()
                .sorted(java.util.Comparator.comparing(PolicyRule::getPriority))
                .map(rule -> new PolicyRuleInfo(
                        rule.getRuleKey(),
                        rule.getPriority(),
                        rule.getClauseRef(),
                        rule.getName(),
                        rule.getScopeJson(),
                        rule.getConditionJson(),
                        rule.getOutcomeJson(),
                        rule.getEnabled()
                ))
                .toList();
        List<PolicyParameterInfo> parameterInfos = parameters.stream()
                .map(parameter -> new PolicyParameterInfo(
                        parameter.getId(),
                        parameter.getParamKey(),
                        parameter.getValueType(),
                        typedValue(parameter),
                        parameter.getScopeJson()
                ))
                .toList();
        return new PolicySnapshot(
                version.getId(),
                version.getLabel(),
                version.getEffectiveFrom(),
                version.getApprovedAt(),
                version.getActivatedAt(),
                version.getContentHash(),
                ruleInfos,
                parameterInfos,
                factMap
        );
    }

    private static Object typedValue(PolicyParameter parameter) {
        JsonNode node = PolicyJson.parse(parameter.getValueJson());
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (parameter.getValueType() == null ? "string" : parameter.getValueType()) {
            case "decimal" -> node.isNumber() ? node.decimalValue() : new java.math.BigDecimal(node.asText());
            case "integer" -> node.asInt();
            case "boolean" -> node.asBoolean();
            default -> node.asText();
        };
    }
}
