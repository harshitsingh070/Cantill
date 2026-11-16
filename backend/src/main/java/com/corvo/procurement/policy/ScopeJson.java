package com.corvo.procurement.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class ScopeJson {
    private static final String[] ORDER = {"item_code", "category", "plant", "item_type"};

    private ScopeJson() {
    }

    public static Map<String, String> parse(String scopeJson) {
        if (scopeJson == null || scopeJson.isBlank()) {
            return Map.of();
        }
        JsonNode node = PolicyJson.parse(scopeJson);
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue() != null && !e.getValue().isNull()) {
                result.put(e.getKey(), e.getValue().asText());
            }
        });
        return result;
    }

    public static String canonical(Map<String, ?> scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        Map<String, Object> ordered = new TreeMap<>();
        for (String key : ORDER) {
            if (scope.containsKey(key) && scope.get(key) != null) {
                ordered.put(key, scope.get(key));
            }
        }
        if (ordered.isEmpty()) {
            return null;
        }
        return PolicyJson.write(ordered);
    }
}
