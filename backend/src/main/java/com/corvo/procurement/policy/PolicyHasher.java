package com.corvo.procurement.policy;

import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PolicyHasher {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PolicyHasher() {
    }

    public static String hash(List<PolicyRule> rules, List<PolicyParameter> parameters) {
        try {
            List<Map<String, Object>> ruleRows = new ArrayList<>();
            rules.stream().sorted(Comparator.comparing(PolicyRule::getRuleKey)).forEach(rule -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rule_key", rule.getRuleKey());
                row.put("priority", rule.getPriority());
                row.put("condition_json", PolicyJson.parse(rule.getConditionJson()));
                row.put("outcome_json", PolicyJson.parse(rule.getOutcomeJson()));
                row.put("enabled", rule.getEnabled());
                ruleRows.add(row);
            });
            List<Map<String, Object>> parameterRows = new ArrayList<>();
            parameters.stream()
                    .sorted(Comparator.comparing(PolicyParameter::getParamKey)
                            .thenComparing(p -> p.getScopeJson() == null ? "" : p.getScopeJson()))
                    .forEach(parameter -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("key", parameter.getParamKey());
                        row.put("value", PolicyJson.parse(parameter.getValueJson()));
                        row.put("value_type", parameter.getValueType());
                        row.put("scope", parameter.getScopeJson() == null ? null : PolicyJson.parse(parameter.getScopeJson()));
                        parameterRows.add(row);
                    });
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("rules", ruleRows);
            document.put("parameters", parameterRows);
            byte[] canonical = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash policy", e);
        }
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash value", e);
        }
    }
}
