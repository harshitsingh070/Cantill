package com.corvo.procurement.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class PolicyJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicyJson() {
    }

    public static JsonNode parse(String json) {
        try {
            return json == null || json.isBlank() ? MAPPER.nullNode() : MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + json, e);
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize JSON", e);
        }
    }
}
