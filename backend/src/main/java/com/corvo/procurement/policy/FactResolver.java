package com.corvo.procurement.policy;

import com.corvo.procurement.engine.model.PolicyFactInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class FactResolver {
    private final PolicySnapshot policy;
    private final ParameterResolver parameterResolver;

    public FactResolver(PolicySnapshot policy, ParameterResolver parameterResolver) {
        this.policy = policy;
        this.parameterResolver = parameterResolver;
    }

    public Map<String, Object> resolve(RequirementFacts facts) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PolicyFactInfo fact : policy.facts().values()) {
            values.put(fact.factKey(), resolveFact(fact, facts));
        }
        return values;
    }

    private Object resolveFact(PolicyFactInfo fact, RequirementFacts facts) {
        JsonNode config = PolicyJson.parse(fact.configJson());
        String resolver = fact.resolverKey();
        return switch (resolver) {
            case "requirement_field" -> requirementField(fieldName(config, fact.factKey()), facts);
            case "item_field" -> itemField(fieldName(config, fact.factKey()), facts);
            case "history_flag" -> facts.hasActiveHistory();
            case "contract_flag" -> contractField(fieldName(config, fact.factKey()), facts);
            case "parameter" -> parameterResolver.resolve(fieldName(config, fact.factKey()), facts);
            default -> throw new IllegalStateException("Unknown resolver_key '" + resolver + "' for fact '" + fact.factKey() + "'");
        };
    }

    private String fieldName(JsonNode config, String fallback) {
        if (config != null && config.hasNonNull("field")) {
            return config.get("field").asText();
        }
        if (config != null && config.hasNonNull("param")) {
            return config.get("param").asText();
        }
        return fallback;
    }

    private Object requirementField(String field, RequirementFacts facts) {
        return switch (field) {
            case "item_code" -> facts.itemCode();
            case "item_type" -> facts.itemType();
            case "category" -> facts.category();
            case "plant" -> facts.plant();
            case "value_inr" -> facts.valueInr();
            case "urgency" -> facts.urgency();
            case "quantity" -> facts.quantity();
            case "uom" -> facts.uom();
            case "need_by_offset_days", "need_by_date_minus_requirement_date" -> facts.needByOffsetDays();
            case "has_applicable_contract" -> facts.hasApplicableContract();
            case "has_contract_with_ceiling" -> facts.hasContractWithCeiling();
            case "has_active_history" -> facts.hasActiveHistory();
            case "is_new_item" -> facts.isNewItem();
            default -> throw new IllegalStateException("Unknown requirement field '" + field + "'");
        };
    }

    private Object itemField(String field, RequirementFacts facts) {
        return switch (field) {
            case "item_code" -> facts.itemCode();
            case "item_type" -> facts.itemType();
            case "category" -> facts.category();
            case "uom" -> facts.uom();
            default -> throw new IllegalStateException("Unknown item field '" + field + "'");
        };
    }

    private Object contractField(String field, RequirementFacts facts) {
        return switch (field) {
            case "has_applicable_contract" -> facts.hasApplicableContract();
            case "has_contract_with_ceiling" -> facts.hasContractWithCeiling();
            default -> throw new IllegalStateException("Unknown contract flag '" + field + "'");
        };
    }
}
