package com.corvo.procurement.policy;

import com.corvo.procurement.engine.model.PolicyFactInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConditionEvaluator {
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "in", "gt", "gte", "lt", "lte", "between", "exists"
    );

    private final PolicySnapshot policy;
    private final ParameterResolver parameterResolver;

    public ConditionEvaluator(PolicySnapshot policy, ParameterResolver parameterResolver) {
        this.policy = policy;
        this.parameterResolver = parameterResolver;
    }

    public boolean evaluate(String conditionJson, Map<String, Object> facts, RequirementFacts requirementFacts) {
        JsonNode root = PolicyJson.parse(conditionJson);
        if (root == null || root.isNull()) {
            throw new ConditionEvaluationException("Rule condition is empty");
        }
        return evaluateNode(root, facts, requirementFacts);
    }

    private boolean evaluateNode(JsonNode node, Map<String, Object> facts, RequirementFacts requirementFacts) {
        if (node.has("all")) {
            for (JsonNode child : node.get("all")) {
                if (!evaluateNode(child, facts, requirementFacts)) {
                    return false;
                }
            }
            return true;
        }
        if (node.has("any")) {
            for (JsonNode child : node.get("any")) {
                if (evaluateNode(child, facts, requirementFacts)) {
                    return true;
                }
            }
            return false;
        }
        if (node.has("not")) {
            return !evaluateNode(node.get("not"), facts, requirementFacts);
        }
        return evaluateLeaf(node, facts, requirementFacts);
    }

    private boolean evaluateLeaf(JsonNode leaf, Map<String, Object> facts, RequirementFacts requirementFacts) {
        if (!leaf.hasNonNull("fact")) {
            throw new ConditionEvaluationException("Condition leaf has no fact: " + leaf);
        }
        String fact = leaf.get("fact").asText();
        if (!facts.containsKey(fact)) {
            throw new ConditionEvaluationException("Unknown fact '" + fact + "'");
        }
        String op = leaf.hasNonNull("op") ? leaf.get("op").asText() : "eq";
        if (!OPERATORS.contains(op)) {
            throw new ConditionEvaluationException("Unknown operator '" + op + "'");
        }
        Object factValue = facts.get(fact);
        if ("exists".equals(op)) {
            if (leaf.has("value") || leaf.has("param")) {
                throw new ConditionEvaluationException("exists accepts neither value nor param");
            }
            return factValue != null;
        }
        boolean hasValue = leaf.has("value");
        boolean hasParam = leaf.has("param");
        if (hasValue == hasParam) {
            throw new ConditionEvaluationException("Condition leaf must contain exactly one of value or param: " + leaf);
        }
        Object comparison = hasParam
                ? parameterResolver.resolve(leaf.get("param").asText(), requirementFacts)
                : literal(leaf.get("value"));

        return switch (op) {
            case "eq" -> equalValues(factValue, comparison);
            case "ne" -> !equalValues(factValue, comparison);
            case "in" -> inValues(factValue, comparison);
            case "gt" -> compare(factValue, comparison) > 0;
            case "gte" -> compare(factValue, comparison) >= 0;
            case "lt" -> compare(factValue, comparison) < 0;
            case "lte" -> compare(factValue, comparison) <= 0;
            case "between" -> between(factValue, comparison);
            default -> throw new ConditionEvaluationException("Unsupported operator '" + op + "'");
        };
    }

    private Object literal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(literal(child)));
            return list;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    private boolean equalValues(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return Boolean.valueOf(String.valueOf(left)).equals(Boolean.valueOf(String.valueOf(right)));
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private boolean inValues(Object factValue, Object comparison) {
        if (comparison == null) {
            return false;
        }
        if (comparison instanceof List<?> list) {
            for (Object item : list) {
                if (equalValues(factValue, item)) {
                    return true;
                }
            }
            return false;
        }
        if (comparison instanceof JsonNode node && node.isArray()) {
            for (JsonNode child : node) {
                if (equalValues(factValue, literal(child))) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private int compare(Object factValue, Object comparison) {
        if (factValue == null || comparison == null) {
            return Integer.MIN_VALUE;
        }
        return decimal(factValue).compareTo(decimal(comparison));
    }

    private boolean between(Object factValue, Object comparison) {
        if (factValue == null || !(comparison instanceof List<?> list) || list.size() != 2) {
            return false;
        }
        BigDecimal value = decimal(factValue);
        return value.compareTo(decimal(list.get(0))) >= 0 && value.compareTo(decimal(list.get(1))) <= 0;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ConditionEvaluationException("Value is not numeric: " + value);
        }
    }

    public void validate(String conditionJson) {
        JsonNode root = PolicyJson.parse(conditionJson);
        if (root == null || root.isNull()) {
            throw new ConditionEvaluationException("Rule condition is empty");
        }
        validateNode(root, policy);
    }

    private void validateNode(JsonNode node, PolicySnapshot snapshot) {
        if (node.has("all") || node.has("any")) {
            String key = node.has("all") ? "all" : "any";
            JsonNode children = node.get(key);
            if (!children.isArray() || children.isEmpty()) {
                throw new ConditionEvaluationException(key + " requires a non-empty array");
            }
            for (JsonNode child : children) {
                validateNode(child, snapshot);
            }
            return;
        }
        if (node.has("not")) {
            validateNode(node.get("not"), snapshot);
            return;
        }
        if (!node.hasNonNull("fact")) {
            throw new ConditionEvaluationException("Condition leaf has no fact");
        }
        String factKey = node.get("fact").asText();
        if (!snapshot.facts().containsKey(factKey)) {
            throw new ConditionEvaluationException("Unknown fact '" + factKey + "'");
        }
        if (!node.hasNonNull("op")) {
            throw new ConditionEvaluationException("Condition leaf has no operator");
        }
        String op = node.get("op").asText();
        if (!OPERATORS.contains(op)) {
            throw new ConditionEvaluationException("Unknown operator '" + op + "'");
        }
        if ("exists".equals(op)) {
            if (node.has("value") || node.has("param")) {
                throw new ConditionEvaluationException("exists accepts neither value nor param");
            }
            return;
        }
        boolean hasValue = node.has("value");
        boolean hasParam = node.has("param");
        if (hasValue == hasParam) {
            throw new ConditionEvaluationException("Condition leaf must contain exactly one of value or param");
        }
        if (hasParam) {
            String param = node.get("param").asText();
            boolean exists = snapshot.parameters().stream().anyMatch(p -> p.key().equals(param));
            if (!exists) {
                throw new ConditionEvaluationException("Unknown parameter '" + param + "'");
            }
        }
        JsonNode value = node.get("value");
        if (value != null && "between".equals(op) && (!value.isArray() || value.size() != 2)) {
            throw new ConditionEvaluationException("between requires a two-element value array");
        }
        if (value != null && "in".equals(op) && !value.isArray()) {
            throw new ConditionEvaluationException("in requires an array value");
        }
        if (hasValue) {
            PolicyFactInfo fact = snapshot.facts().get(factKey);
            if ("boolean".equals(fact.valueType()) && !value.isBoolean()) {
                throw new ConditionEvaluationException("Fact '" + factKey + "' expects a boolean");
            }
            if (("decimal".equals(fact.valueType()) || "integer".equals(fact.valueType()))
                    && !value.isNumber() && !"between".equals(op) && !"in".equals(op)) {
                throw new ConditionEvaluationException("Fact '" + factKey + "' expects a numeric value");
            }
        }
    }
}
