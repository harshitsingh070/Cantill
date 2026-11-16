package com.corvo.procurement.policy;

import com.corvo.procurement.engine.model.PolicyParameterInfo;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ParameterResolver {
    private final List<PolicyParameterInfo> parameters;

    public ParameterResolver(List<PolicyParameterInfo> parameters) {
        this.parameters = parameters == null ? List.of() : parameters;
    }

    public Object resolve(String key, RequirementFacts facts) {
        List<PolicyParameterInfo> matching = new ArrayList<>();
        for (PolicyParameterInfo parameter : parameters) {
            if (!parameter.key().equals(key)) {
                continue;
            }
            if (scopeMatches(parameter.scopeJson(), facts)) {
                matching.add(parameter);
            }
        }
        if (matching.isEmpty()) {
            throw new ParameterResolutionException("No value for policy parameter '" + key + "'");
        }
        matching.sort(Comparator
                .comparingInt((PolicyParameterInfo p) -> specificity(p.scopeJson()))
                .thenComparing(PolicyParameterInfo::id, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        PolicyParameterInfo chosen = matching.get(0);
        return typedValue(chosen);
    }

    public BigDecimal resolveDecimal(String key, RequirementFacts facts) {
        Object value = resolve(key, facts);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    public int resolveInt(String key, RequirementFacts facts) {
        Object value = resolve(key, facts);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean scopeMatches(String scopeJson, RequirementFacts facts) {
        Map<String, String> scope = ScopeJson.parse(scopeJson);
        if (scope.isEmpty()) {
            return true;
        }
        if (scope.containsKey("item_code") && !scope.get("item_code").equals(facts.itemCode())) {
            return false;
        }
        if (scope.containsKey("category") && !scope.get("category").equals(facts.category())) {
            return false;
        }
        if (scope.containsKey("plant") && (facts.plant() == null || !scope.get("plant").equals(facts.plant()))) {
            return false;
        }
        return true;
    }

    private int specificity(String scopeJson) {
        Map<String, String> scope = ScopeJson.parse(scopeJson);
        if (scope.containsKey("item_code")) {
            return 4;
        }
        if (scope.containsKey("category") && scope.containsKey("plant")) {
            return 3;
        }
        if (scope.containsKey("plant")) {
            return 2;
        }
        if (scope.containsKey("category")) {
            return 1;
        }
        return 0;
    }

    private Object typedValue(PolicyParameterInfo parameter) {
        Object value = parameter.value();
        String type = parameter.valueType() == null ? "string" : parameter.valueType();
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "decimal" -> value instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(value));
            case "integer" -> value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
            case "boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            default -> value instanceof String s ? s : String.valueOf(value);
        };
    }

    public static Object literalValue(JsonNode node, String valueType) {
        if (node == null || node.isNull()) {
            return null;
        }
        if ("decimal".equals(valueType) || node.isNumber()) {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            return new BigDecimal(node.asText());
        }
        if ("integer".equals(valueType)) {
            return node.asInt();
        }
        if ("boolean".equals(valueType)) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
