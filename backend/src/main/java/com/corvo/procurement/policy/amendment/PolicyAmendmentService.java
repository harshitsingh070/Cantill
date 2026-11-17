package com.corvo.procurement.policy.amendment;

import com.corvo.procurement.domain.AuditLog;
import com.corvo.procurement.domain.AuditLogRepository;
import com.corvo.procurement.domain.PolicyAmendment;
import com.corvo.procurement.domain.PolicyAmendmentRepository;
import com.corvo.procurement.domain.PolicyChangeLog;
import com.corvo.procurement.domain.PolicyChangeLogRepository;
import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyFactRepository;
import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyParameterRepository;
import com.corvo.procurement.domain.PolicyRule;
import com.corvo.procurement.domain.PolicyRuleRepository;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.domain.PolicyVersionRepository;
import com.corvo.procurement.domain.Supplier;
import com.corvo.procurement.domain.SupplierRepository;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.policy.ConditionEvaluationException;
import com.corvo.procurement.policy.ConditionEvaluator;
import com.corvo.procurement.policy.ParameterResolver;
import com.corvo.procurement.policy.PolicyHasher;
import com.corvo.procurement.policy.PolicyJson;
import com.corvo.procurement.policy.PolicyMapper;
import com.corvo.procurement.policy.ScopeJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PolicyAmendmentService {
    public static final Set<String> RULE_SCOPE_KEYS = Set.of("item_code", "category", "plant", "item_type");
    public static final Set<String> PARAMETER_SCOPE_KEYS = Set.of("item_code", "category", "plant");
    private static final Set<String> ROUTES = Set.of(
            "CONTRACT_PO", "EMERGENCY_PO", "SPOT_PO", "RFQ", "RFP", "CAPEX_SOURCING",
            "NEW_ITEM_SOURCING", "NOT_PROCESSED"
    );
    private static final Map<String, Set<String>> ROUTE_STRATEGIES = Map.of(
            "CONTRACT_PO", Set.of("contract_lowest_rate_with_ceiling"),
            "EMERGENCY_PO", Set.of("first_preferred_supplier"),
            "SPOT_PO", Set.of("first_preferred_supplier"),
            "RFQ", Set.of("preferred_top_n", "category_top_n"),
            "RFP", Set.of("preferred_top_n", "category_top_n"),
            "CAPEX_SOURCING", Set.of("none"),
            "NEW_ITEM_SOURCING", Set.of("none"),
            "NOT_PROCESSED", Set.of("none")
    );
    private static final Map<String, Integer> ROUTE_COUNTS = Map.of(
            "CONTRACT_PO", 0,
            "EMERGENCY_PO", 0,
            "SPOT_PO", 0,
            "RFQ", 3,
            "RFP", 5,
            "CAPEX_SOURCING", 5,
            "NEW_ITEM_SOURCING", 0,
            "NOT_PROCESSED", 0
    );
    private static final List<String> REQUIRED_PARAMETERS = List.of(
            "emergency_limit", "spot_limit", "rfq_rfp_boundary", "club_window_days",
            "need_by_window_days", "consolidation_hours", "active_history_days",
            "valuation_history_days", "preferred_history_days", "recency_fresh_days",
            "recency_usable_days", "suggestion_count_rfq", "suggestion_count_rfp",
            "suggestion_count_capex", "item_match_threshold", "item_match_token_weight",
            "item_match_fuzzy_weight", "ranking_weight_recency", "ranking_weight_frequency",
            "ranking_weight_contract", "ranking_weight_on_time", "ranking_weight_quality",
            "topup_weight_on_time", "topup_weight_quality"
    );

    private final PolicyAmendmentRepository amendmentRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyRuleRepository ruleRepository;
    private final PolicyParameterRepository parameterRepository;
    private final PolicyFactRepository factRepository;
    private final PolicyChangeLogRepository changeLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final SupplierRepository supplierRepository;
    private final ObjectMapper objectMapper;

    public PolicyAmendmentService(PolicyAmendmentRepository amendmentRepository,
                                  PolicyVersionRepository versionRepository,
                                  PolicyRuleRepository ruleRepository,
                                  PolicyParameterRepository parameterRepository,
                                  PolicyFactRepository factRepository,
                                  PolicyChangeLogRepository changeLogRepository,
                                  AuditLogRepository auditLogRepository,
                                  SupplierRepository supplierRepository,
                                  ObjectMapper objectMapper) {
        this.amendmentRepository = amendmentRepository;
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.parameterRepository = parameterRepository;
        this.factRepository = factRepository;
        this.changeLogRepository = changeLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.supplierRepository = supplierRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PolicyAmendment create(String source, String rawText, String documentRef,
                                  LocalDate effectiveFrom, List<Map<String, Object>> operations,
                                  String createdBy) {
        if (effectiveFrom == null) {
            throw new PolicyValidationException("Effective date is required");
        }
        PolicyAmendment amendment = new PolicyAmendment();
        amendment.setSource(source == null ? "manual" : source);
        amendment.setRawText(rawText);
        amendment.setDocumentRef(documentRef);
        amendment.setEffectiveFrom(effectiveFrom);
        amendment.setCreatedBy(createdBy == null ? "buyer" : createdBy);
        amendment.setStatus("DRAFT");
        amendment.setOperationsJson(write(operations == null ? List.of() : operations));
        amendment.setExtractedJson(write(Map.of(
                "effective_from", effectiveFrom.toString(),
                "summary", "",
                "operations", operations == null ? List.of() : operations,
                "warnings", List.of(),
                "confidence", 0.0
        )));
        return amendmentRepository.save(amendment);
    }

    public List<PolicyAmendment> list() {
        return amendmentRepository.findAll().stream()
                .sorted(Comparator.comparing(PolicyAmendment::getId).reversed())
                .toList();
    }

    public PolicyAmendment get(Long id) {
        return amendmentRepository.findById(id)
                .orElseThrow(() -> new PolicyValidationException("Amendment not found: " + id));
    }

    @Transactional
    public PolicyAmendment addOperation(Long id, Map<String, Object> operation, String actor) {
        PolicyAmendment amendment = get(id);
        if (!"DRAFT".equals(amendment.getStatus()) && !"VALIDATED".equals(amendment.getStatus())) {
            throw new PolicyValidationException("Only DRAFT or VALIDATED amendments are mutable");
        }
        List<Map<String, Object>> operations = readOperations(amendment.getOperationsJson());
        operations.add(operation);
        if ("set_effective_from".equals(operation.get("type"))) {
            Object date = operation.get("date");
            if (date != null) {
                amendment.setEffectiveFrom(LocalDate.parse(String.valueOf(date)));
            }
        }
        amendment.setOperationsJson(write(operations));
        amendment.setStatus("DRAFT");
        return amendmentRepository.save(amendment);
    }

    @Transactional
    public Map<String, Object> validate(Long id) {
        PolicyAmendment amendment = get(id);
        requireEditable(amendment);
        PolicyDraft draft = materialize(amendment);
        List<String> errors = validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new PolicyValidationException(String.join("; ", errors));
        }
        amendment.setStatus("VALIDATED");
        amendmentRepository.save(amendment);
        return Map.of("valid", true, "effective_from", draft.effectiveFrom().toString(),
                "rules", draft.rules().size(), "parameters", draft.parameters().size());
    }

    @Transactional
    public Map<String, Object> preview(Long id) {
        PolicyAmendment amendment = get(id);
        requireEditable(amendment);
        PolicyDraft draft = materialize(amendment);
        List<String> errors = validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new PolicyValidationException(String.join("; ", errors));
        }
        PolicyDraft parent = parentDraft(amendment, draft.effectiveFrom());
        Map<String, Object> diff = diff(parent, draft);
        String hash = PolicyHasher.hash(draft.rules(), draft.parameters());
        diff.put("content_hash", hash);
        amendment.setDiffJson(write(diff));
        amendment.setStatus("VALIDATED");
        amendmentRepository.save(amendment);
        return diff;
    }

    public Map<String, Object> diff(Long id) {
        PolicyAmendment amendment = get(id);
        if (amendment.getDiffJson() == null) {
            return preview(id);
        }
        return readMap(amendment.getDiffJson());
    }

    @Transactional
    public PolicyVersion approve(Long id, String actor, boolean twoStep) {
        PolicyAmendment amendment = get(id);
        if ("APPLIED".equals(amendment.getStatus()) || "REJECTED".equals(amendment.getStatus())
                || "APPROVED".equals(amendment.getStatus())) {
            throw new PolicyValidationException("Amendment cannot be approved in state " + amendment.getStatus());
        }
        PolicyDraft draft = materialize(amendment);
        List<String> errors = validateDraft(draft);
        if (!errors.isEmpty()) {
            throw new PolicyValidationException(String.join("; ", errors));
        }
        String hash = PolicyHasher.hash(draft.rules(), draft.parameters());
        if (amendment.getDiffJson() != null) {
            Object previewHash = readMap(amendment.getDiffJson()).get("content_hash");
            if (previewHash != null && !hash.equals(String.valueOf(previewHash))) {
                throw new PolicyValidationException("Stale draft: content hash changed since preview");
            }
        }
        PolicyVersion parent = parentVersion(draft.effectiveFrom()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        PolicyVersion version = new PolicyVersion();
        version.setParentVersionId(parent == null ? null : parent.getId());
        version.setLabel(nextLabel(amendment, parent));
        version.setDocumentRef(parent == null ? "manual amendment" : parent.getDocumentRef());
        version.setEffectiveFrom(draft.effectiveFrom());
        version.setStatus(twoStep ? "APPROVED" : activationStatus(draft.effectiveFrom()));
        version.setSource(amendment.getSource());
        version.setContentHash(hash);
        version.setApprovedBy(actor == null ? "procurement" : actor);
        version.setApprovedAt(now);
        version.setActivatedAt(twoStep ? null : now);
        version.setCreatedAt(now);
        version.setNotes("Created from amendment " + amendment.getId());
        version = versionRepository.saveAndFlush(version);

        List<PolicyRule> savedRules = new ArrayList<>();
        for (PolicyRule rule : draft.rules()) {
            PolicyRule row = cloneRule(rule);
            row.setVersionId(version.getId());
            savedRules.add(row);
        }
        ruleRepository.saveAll(savedRules);
        List<PolicyParameter> savedParameters = new ArrayList<>();
        for (PolicyParameter parameter : draft.parameters()) {
            PolicyParameter row = cloneParameter(parameter);
            row.setVersionId(version.getId());
            savedParameters.add(row);
        }
        parameterRepository.saveAll(savedParameters);

        if (!twoStep && "ACTIVE".equals(version.getStatus()) && parent != null) {
            retireOlder(parent, version);
        }

        amendment.setApprovedBy(actor == null ? "procurement" : actor);
        amendment.setApprovedAt(now);
        amendment.setAppliedVersionId(version.getId());
        amendment.setAppliedAt(twoStep ? null : now);
        amendment.setStatus(twoStep ? "APPROVED" : "APPLIED");
        amendmentRepository.save(amendment);

        Map<String, Object> diff = readMap(amendment.getDiffJson());
        List<Map<String, Object>> changes = (List<Map<String, Object>>) diff.getOrDefault("changes", List.of());
        for (Map<String, Object> change : changes) {
            PolicyChangeLog log = new PolicyChangeLog();
            log.setVersionId(version.getId());
            log.setOperation(String.valueOf(change.get("kind")));
            log.setTarget(String.valueOf(change.get("target")));
            log.setBeforeJson(write(change.get("before")));
            log.setAfterJson(write(change.get("after")));
            log.setActor(actor);
            log.setCreatedAt(now);
            changeLogRepository.save(log);
        }
        AuditLog audit = new AuditLog();
        audit.setActor(actor);
        audit.setAction("POLICY_APPROVED");
        audit.setTarget("version:" + version.getId());
        audit.setAfterJson(write(Map.of("label", version.getLabel(), "effective_from", version.getEffectiveFrom().toString())));
        audit.setCreatedAt(now);
        auditLogRepository.save(audit);
        return version;
    }

    @Transactional
    public PolicyVersion activate(Long amendmentId, String actor) {
        PolicyAmendment amendment = get(amendmentId);
        if (!"APPROVED".equals(amendment.getStatus())) {
            throw new PolicyValidationException("Only APPROVED amendments can be activated");
        }
        if (amendment.getAppliedVersionId() == null) {
            throw new PolicyValidationException("Amendment has no approved version");
        }
        PolicyVersion version = versionRepository.findById(amendment.getAppliedVersionId())
                .orElseThrow(() -> new PolicyValidationException("Version not found"));
        version.setActivatedAt(LocalDateTime.now());
        version.setStatus(activationStatus(version.getEffectiveFrom()));
        version = versionRepository.save(version);
        amendment.setStatus("APPLIED");
        amendment.setAppliedAt(LocalDateTime.now());
        amendmentRepository.save(amendment);
        return version;
    }

    @Transactional
    public PolicyAmendment reject(Long id, String actor) {
        PolicyAmendment amendment = get(id);
        if ("APPLIED".equals(amendment.getStatus()) || "REJECTED".equals(amendment.getStatus())) {
            throw new PolicyValidationException("Amendment cannot be rejected in state " + amendment.getStatus());
        }
        amendment.setStatus("REJECTED");
        amendmentRepository.save(amendment);
        AuditLog audit = new AuditLog();
        audit.setActor(actor);
        audit.setAction("POLICY_REJECTED");
        audit.setTarget("amendment:" + id);
        audit.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(audit);
        return amendment;
    }

    public PolicyDraft draft(Long id) {
        return materialize(get(id));
    }

    public List<Map<String, Object>> operations(Long id) {
        return readOperations(get(id).getOperationsJson());
    }

    public PolicyDraft materialize(PolicyAmendment amendment) {
        PolicyVersion parent = parentVersion(amendment.getEffectiveFrom())
                .orElseThrow(() -> new PolicyValidationException("No parent policy version for effective date "
                        + amendment.getEffectiveFrom()));
        List<PolicyRule> rules = ruleRepository.findByVersionIdOrderByPriorityAsc(parent.getId()).stream()
                .map(this::cloneRule)
                .collect(Collectors.toCollection(ArrayList::new));
        List<PolicyParameter> parameters = parameterRepository.findByVersionId(parent.getId()).stream()
                .map(this::cloneParameter)
                .collect(Collectors.toCollection(ArrayList::new));
        LocalDate effective = amendment.getEffectiveFrom();
        for (Map<String, Object> operation : readOperations(amendment.getOperationsJson())) {
            String type = String.valueOf(operation.get("type"));
            switch (type) {
                case "set_parameter" -> applySetParameter(operation, parameters);
                case "add_rule" -> applyAddRule(operation, rules);
                case "update_rule" -> applyUpdateRule(operation, rules);
                case "remove_rule" -> applyRemoveRule(operation, rules);
                case "reorder_rule" -> applyReorderRule(operation, rules);
                case "set_rule_enabled" -> applySetEnabled(operation, rules);
                case "set_effective_from" -> {
                    Object date = operation.get("date");
                    if (date != null) {
                        effective = LocalDate.parse(String.valueOf(date));
                    }
                }
                default -> throw new PolicyValidationException("Unknown operation type '" + type + "'");
            }
        }
        return new PolicyDraft(effective, rules, parameters);
    }

    private void requireEditable(PolicyAmendment amendment) {
        if (!"DRAFT".equals(amendment.getStatus()) && !"VALIDATED".equals(amendment.getStatus())) {
            throw new PolicyValidationException("Only DRAFT or VALIDATED amendments are editable");
        }
    }

    private void applyAddRule(Map<String, Object> operation, List<PolicyRule> rules) {
        String key = stringValue(operation.get("rule_key"));
        if (key == null) {
            throw new PolicyValidationException("add_rule requires rule_key");
        }
        if (rules.stream().anyMatch(r -> key.equals(r.getRuleKey()))) {
            throw new PolicyValidationException("Duplicate rule_key '" + key + "'");
        }
        String scope = operation.containsKey("scope_json") ? canonicalScope(operation.get("scope_json"), RULE_SCOPE_KEYS) : null;
        String core = jsonString(operation.get("condition_json"));
        if (core == null) {
            throw new PolicyValidationException("add_rule requires condition_json");
        }
        PolicyRule rule = new PolicyRule();
        rule.setRuleKey(key);
        rule.setPriority(integerValue(operation.get("priority")));
        rule.setClauseRef(stringValue(operation.get("clause_ref")));
        rule.setName(stringValue(operation.get("name")));
        rule.setScopeJson(scope);
        rule.setConditionJson(mergeScope(scope, core));
        rule.setOutcomeJson(jsonString(operation.get("outcome_json")));
        rule.setEnabled(operation.get("enabled") == null || Boolean.parseBoolean(String.valueOf(operation.get("enabled"))));
        rules.add(rule);
    }

    private void applyUpdateRule(Map<String, Object> operation, List<PolicyRule> rules) {
        String key = stringValue(operation.get("rule_key"));
        PolicyRule rule = rules.stream().filter(r -> r.getRuleKey().equals(key)).findFirst()
                .orElseThrow(() -> new PolicyValidationException("Unknown rule '" + key + "'"));
        Map<String, Object> patch = asMap(operation.get("patch"));
        if (patch == null) {
            patch = operation;
        }
        String oldScope = rule.getScopeJson();
        String core;
        if (patch.containsKey("condition_json")) {
            core = jsonString(patch.get("condition_json"));
        } else {
            core = stripLeadingScope(rule.getConditionJson(), oldScope);
        }
        String newScope = patch.containsKey("scope_json") ? canonicalScope(patch.get("scope_json"), RULE_SCOPE_KEYS) : oldScope;
        rule.setScopeJson(newScope);
        rule.setConditionJson(mergeScope(newScope, core));
        if (patch.containsKey("priority")) {
            rule.setPriority(integerValue(patch.get("priority")));
        }
        if (patch.containsKey("clause_ref")) {
            rule.setClauseRef(stringValue(patch.get("clause_ref")));
        }
        if (patch.containsKey("name")) {
            rule.setName(stringValue(patch.get("name")));
        }
        if (patch.containsKey("outcome_json")) {
            rule.setOutcomeJson(jsonString(patch.get("outcome_json")));
        }
        if (patch.containsKey("enabled")) {
            rule.setEnabled(Boolean.parseBoolean(String.valueOf(patch.get("enabled"))));
        }
    }

    private void applyRemoveRule(Map<String, Object> operation, List<PolicyRule> rules) {
        String key = stringValue(operation.get("rule_key"));
        rules.removeIf(rule -> rule.getRuleKey().equals(key));
    }

    private void applyReorderRule(Map<String, Object> operation, List<PolicyRule> rules) {
        String key = stringValue(operation.get("rule_key"));
        PolicyRule rule = rules.stream().filter(r -> r.getRuleKey().equals(key)).findFirst()
                .orElseThrow(() -> new PolicyValidationException("Unknown rule '" + key + "'"));
        rule.setPriority(integerValue(operation.get("priority")));
    }

    private void applySetEnabled(Map<String, Object> operation, List<PolicyRule> rules) {
        String key = stringValue(operation.get("rule_key"));
        PolicyRule rule = rules.stream().filter(r -> r.getRuleKey().equals(key)).findFirst()
                .orElseThrow(() -> new PolicyValidationException("Unknown rule '" + key + "'"));
        rule.setEnabled(Boolean.parseBoolean(String.valueOf(operation.get("enabled"))));
    }

    private void applySetParameter(Map<String, Object> operation, List<PolicyParameter> parameters) {
        String key = stringValue(operation.get("key"));
        if (key == null) {
            throw new PolicyValidationException("set_parameter requires key");
        }
        String scope = operation.containsKey("scope_json")
                ? canonicalScope(operation.get("scope_json"), PARAMETER_SCOPE_KEYS) : null;
        PolicyParameter existingForType = parameters.stream()
                .filter(p -> p.getParamKey().equals(key)).findFirst().orElse(null);
        String type = operation.get("value_type") == null
                ? existingForType == null ? inferType(operation.get("value")) : existingForType.getValueType()
                : String.valueOf(operation.get("value_type"));
        String value = jsonString(operation.get("value"));
        Optional<PolicyParameter> existing = parameters.stream()
                .filter(p -> p.getParamKey().equals(key) && Objects.equals(p.getScopeJson(), scope))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setValueJson(value);
            existing.get().setValueType(type);
        } else {
            PolicyParameter parameter = new PolicyParameter();
            parameter.setParamKey(key);
            parameter.setValueJson(value);
            parameter.setValueType(type);
            parameter.setScopeJson(scope);
            parameters.add(parameter);
        }
    }

    private List<String> validateDraft(PolicyDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft.effectiveFrom() == null) {
            errors.add("Effective date is required");
        }
        PolicyVersion fake = new PolicyVersion();
        fake.setId(0L);
        fake.setLabel("draft");
        fake.setEffectiveFrom(draft.effectiveFrom() == null ? LocalDate.now() : draft.effectiveFrom());
        fake.setApprovedAt(LocalDateTime.now());
        fake.setActivatedAt(LocalDateTime.now());
        PolicySnapshot snapshot = PolicyMapper.snapshot(fake, draft.rules(), draft.parameters(), factRepository.findAll());
        ConditionEvaluator evaluator = new ConditionEvaluator(snapshot, new ParameterResolver(snapshot.parameters()));

        Set<String> ruleKeys = new LinkedHashSet<>();
        Set<Integer> priorities = new LinkedHashSet<>();
        for (PolicyRule rule : draft.rules()) {
            if (rule.getRuleKey() == null || rule.getRuleKey().isBlank()) {
                errors.add("Rule key is required");
                continue;
            }
            if (!ruleKeys.add(rule.getRuleKey())) {
                errors.add("Duplicate rule_key '" + rule.getRuleKey() + "'");
            }
            if (rule.getPriority() == null) {
                errors.add("Rule " + rule.getRuleKey() + " has no priority");
            } else if (!priorities.add(rule.getPriority())) {
                errors.add("Duplicate priority " + rule.getPriority());
            }
            try {
                String scope = rule.getScopeJson();
                if (scope != null) {
                    Set<String> keys = ScopeJson.parse(scope).keySet();
                    for (String key : keys) {
                        if (!RULE_SCOPE_KEYS.contains(key)) {
                            errors.add("Invalid rule scope key '" + key + "'");
                        }
                    }
                }
                evaluator.validate(rule.getConditionJson());
                validateParamTypes(rule.getConditionJson(), snapshot, errors);
                validateOutcome(rule, errors);
            } catch (ConditionEvaluationException e) {
                errors.add("Rule " + rule.getRuleKey() + ": " + e.getMessage());
            }
        }
        Set<String> parameterScopes = new LinkedHashSet<>();
        for (PolicyParameter parameter : draft.parameters()) {
            if (parameter.getParamKey() == null || parameter.getValueJson() == null) {
                errors.add("Parameter key and value are required");
                continue;
            }
            if (parameter.getScopeJson() != null) {
                Set<String> keys = ScopeJson.parse(parameter.getScopeJson()).keySet();
                for (String key : keys) {
                    if (!PARAMETER_SCOPE_KEYS.contains(key)) {
                        errors.add("Invalid parameter scope key '" + key + "'");
                    }
                }
            }
            String key = parameter.getParamKey() + "|" + (parameter.getScopeJson() == null ? "" : parameter.getScopeJson());
            if (!parameterScopes.add(key)) {
                errors.add("Duplicate parameter scope for '" + parameter.getParamKey() + "'");
            }
        }
        for (String required : REQUIRED_PARAMETERS) {
            if (draft.parameters().stream().noneMatch(p -> required.equals(p.getParamKey()))) {
                errors.add("Required parameter '" + required + "' is missing");
            }
        }
        return errors;
    }

    private void validateParamTypes(String conditionJson, PolicySnapshot snapshot, List<String> errors) {
        JsonNode node = PolicyJson.parse(conditionJson);
        walkLeaves(node, leaf -> {
            if (leaf.hasNonNull("param") && leaf.hasNonNull("fact")) {
                String param = leaf.get("param").asText();
                String fact = leaf.get("fact").asText();
                String paramType = snapshot.parameters().stream()
                        .filter(p -> p.key().equals(param))
                        .map(p -> p.valueType()).findFirst().orElse(null);
                String factType = snapshot.facts().get(fact).valueType();
                if (paramType != null && !compatible(paramType, factType)) {
                    errors.add("Parameter '" + param + "' type " + paramType + " is incompatible with fact '" + fact + "'");
                }
            }
        });
    }

    private void walkLeaves(JsonNode node, java.util.function.Consumer<JsonNode> consumer) {
        if (node == null) {
            return;
        }
        if (node.has("all")) {
            node.get("all").forEach(child -> walkLeaves(child, consumer));
        } else if (node.has("any")) {
            node.get("any").forEach(child -> walkLeaves(child, consumer));
        } else if (node.has("not")) {
            walkLeaves(node.get("not"), consumer);
        } else if (node.has("fact")) {
            consumer.accept(node);
        }
    }

    private boolean compatible(String parameterType, String factType) {
        if (parameterType.equals(factType)) {
            return true;
        }
        return Set.of("decimal", "integer").contains(parameterType)
                && Set.of("decimal", "integer").contains(factType);
    }

    private void validateOutcome(PolicyRule rule, List<String> errors) {
        JsonNode outcome;
        try {
            outcome = PolicyJson.parse(rule.getOutcomeJson());
        } catch (Exception e) {
            errors.add("Rule " + rule.getRuleKey() + " has invalid outcome JSON");
            return;
        }
        if (outcome == null || !outcome.isObject()) {
            errors.add("Rule " + rule.getRuleKey() + " has no outcome");
            return;
        }
        String route = outcome.hasNonNull("route") ? outcome.get("route").asText() : null;
        if (route == null || !ROUTES.contains(route)) {
            errors.add("Rule " + rule.getRuleKey() + " has invalid route '" + route + "'");
            return;
        }
        String decision = outcome.hasNonNull("decision") ? outcome.get("decision").asText() : "";
        String expectedDecision = switch (route) {
            case "CONTRACT_PO", "EMERGENCY_PO", "SPOT_PO" -> "PO";
            case "NOT_PROCESSED" -> "";
            default -> "SOURCING_REQUEST";
        };
        if (!expectedDecision.equals(decision)) {
            errors.add("Rule " + rule.getRuleKey() + " decision does not match route");
        }
        String strategy = outcome.hasNonNull("supplier_strategy")
                ? outcome.get("supplier_strategy").asText() : "none";
        if (!ROUTE_STRATEGIES.get(route).contains(strategy)) {
            errors.add("Rule " + rule.getRuleKey() + " has invalid supplier strategy '" + strategy + "'");
        }
        Integer count = outcome.hasNonNull("suggestion_count") ? outcome.get("suggestion_count").asInt() : null;
        Integer allowed = ROUTE_COUNTS.get(route);
        if (count != null && !count.equals(allowed)) {
            errors.add("Rule " + rule.getRuleKey() + " suggestion_count " + count + " is invalid for " + route);
        }
        if (outcome.hasNonNull("supplier_id")) {
            String supplierId = outcome.get("supplier_id").asText();
            Supplier supplier = supplierRepository.findById(supplierId).orElse(null);
            if (supplier == null || !"Active".equalsIgnoreCase(supplier.getStatus())) {
                errors.add("Rule " + rule.getRuleKey() + " references a Blocked/Inactive supplier");
            }
        }
    }

    private Map<String, Object> diff(PolicyDraft parent, PolicyDraft draft) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, PolicyParameter> parentParams = parent.parameters().stream()
                .collect(Collectors.toMap(p -> p.getParamKey() + "|" + nullSafe(p.getScopeJson()),
                        p -> p, (a, b) -> b, LinkedHashMap::new));
        Map<String, PolicyParameter> draftParams = draft.parameters().stream()
                .collect(Collectors.toMap(p -> p.getParamKey() + "|" + nullSafe(p.getScopeJson()),
                        p -> p, (a, b) -> b, LinkedHashMap::new));
        for (Map.Entry<String, PolicyParameter> entry : draftParams.entrySet()) {
            PolicyParameter before = parentParams.get(entry.getKey());
            PolicyParameter after = entry.getValue();
            if (before == null || !Objects.equals(before.getValueJson(), after.getValueJson())) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("kind", before == null ? "parameter_added" : "parameter_changed");
                change.put("target", after.getParamKey());
                change.put("scope", after.getScopeJson() == null ? Map.of() : ScopeJson.parse(after.getScopeJson()));
                change.put("before", before == null ? null : PolicyJson.parse(before.getValueJson()));
                change.put("after", PolicyJson.parse(after.getValueJson()));
                String beforeText = before == null ? "unset" : before.getValueJson();
                if (before == null && after.getScopeJson() != null && !ScopeJson.parse(after.getScopeJson()).isEmpty()) {
                    PolicyParameter global = parentParams.get(after.getParamKey() + "|");
                    if (global != null) {
                        beforeText = "global " + global.getValueJson();
                    }
                }
                change.put("plain_language", "From " + draft.effectiveFrom() + ", " + after.getParamKey()
                        + " changes from " + beforeText
                        + " to " + after.getValueJson() + scopeText(after.getScopeJson()));
                changes.add(change);
            }
        }
        for (String key : parentParams.keySet()) {
            if (!draftParams.containsKey(key)) {
                PolicyParameter before = parentParams.get(key);
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("kind", "parameter_removed");
                change.put("target", before.getParamKey());
                change.put("scope", before.getScopeJson() == null ? Map.of() : ScopeJson.parse(before.getScopeJson()));
                change.put("before", PolicyJson.parse(before.getValueJson()));
                change.put("after", null);
                change.put("plain_language", "From " + draft.effectiveFrom() + ", " + before.getParamKey() + " is removed");
                changes.add(change);
            }
        }
        Map<String, PolicyRule> parentRules = parent.rules().stream()
                .collect(Collectors.toMap(PolicyRule::getRuleKey, r -> r, (a, b) -> b, LinkedHashMap::new));
        Map<String, PolicyRule> draftRules = draft.rules().stream()
                .collect(Collectors.toMap(PolicyRule::getRuleKey, r -> r, (a, b) -> b, LinkedHashMap::new));
        for (Map.Entry<String, PolicyRule> entry : draftRules.entrySet()) {
            PolicyRule before = parentRules.get(entry.getKey());
            PolicyRule after = entry.getValue();
            if (before == null) {
                changes.add(ruleChange("rule_added", after, null, after));
            } else {
                if (!Objects.equals(before.getPriority(), after.getPriority())) {
                    Map<String, Object> change = ruleChange("rule_reordered", after, before, after);
                    change.put("plain_language", "Rule " + after.getRuleKey() + " moves to priority " + after.getPriority());
                    changes.add(change);
                }
                if (!Objects.equals(before.getConditionJson(), after.getConditionJson())) {
                    changes.add(ruleChange("rule_condition_changed", after, before, after));
                }
                if (!Objects.equals(before.getEnabled(), after.getEnabled())) {
                    Map<String, Object> change = ruleChange("rule_toggled", after, before, after);
                    change.put("plain_language", "Rule " + after.getRuleKey() + " is "
                            + (Boolean.TRUE.equals(after.getEnabled()) ? "enabled" : "disabled"));
                    changes.add(change);
                }
                if (!Objects.equals(before.getScopeJson(), after.getScopeJson())) {
                    changes.add(ruleChange("rule_scope_changed", after, before, after));
                }
            }
        }
        for (String key : parentRules.keySet()) {
            if (!draftRules.containsKey(key)) {
                PolicyRule before = parentRules.get(key);
                changes.add(ruleChange("rule_removed", before, before, null));
            }
        }
        String summary;
        if (changes.isEmpty()) {
            summary = "No policy changes from " + draft.effectiveFrom();
        } else {
            summary = changes.stream().limit(4).map(c -> String.valueOf(c.get("plain_language")))
                    .collect(Collectors.joining("; "));
            if (changes.size() > 4) {
                summary += "; and " + (changes.size() - 4) + " more changes";
            }
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("effective_from", draft.effectiveFrom().toString());
        diff.put("summary", summary);
        diff.put("changes", changes);
        return diff;
    }

    private Map<String, Object> ruleChange(String kind, PolicyRule target, PolicyRule before, PolicyRule after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("kind", kind);
        change.put("target", target.getRuleKey());
        change.put("scope", target.getScopeJson() == null ? Map.of() : ScopeJson.parse(target.getScopeJson()));
        change.put("before", before == null ? null : Map.of(
                "priority", before.getPriority(),
                "condition", PolicyJson.parse(before.getConditionJson()),
                "outcome", PolicyJson.parse(before.getOutcomeJson()),
                "enabled", before.getEnabled()
        ));
        change.put("after", after == null ? null : Map.of(
                "priority", after.getPriority(),
                "condition", PolicyJson.parse(after.getConditionJson()),
                "outcome", PolicyJson.parse(after.getOutcomeJson()),
                "enabled", after.getEnabled()
        ));
        String plain = switch (kind) {
            case "rule_added" -> "Rule " + target.getRuleKey() + " is added with route "
                    + outcomeRoute(target) + scopeText(target.getScopeJson());
            case "rule_removed" -> "Rule " + target.getRuleKey() + " is removed";
            case "rule_condition_changed" -> "Rule " + target.getRuleKey() + " condition changes";
            case "rule_scope_changed" -> "Rule " + target.getRuleKey() + " scope changes" + scopeText(target.getScopeJson());
            default -> "Rule " + target.getRuleKey() + " changes";
        };
        change.put("plain_language", plain);
        return change;
    }

    private String outcomeRoute(PolicyRule rule) {
        JsonNode node = PolicyJson.parse(rule.getOutcomeJson());
        return node != null && node.hasNonNull("route") ? node.get("route").asText() : "unknown";
    }

    private String scopeText(String scopeJson) {
        if (scopeJson == null) {
            return "";
        }
        Map<String, String> scope = ScopeJson.parse(scopeJson);
        return scope.isEmpty() ? "" : " for " + scope;
    }

    private PolicyDraft parentDraft(PolicyAmendment amendment, LocalDate effectiveFrom) {
        PolicyVersion parent = parentVersion(effectiveFrom)
                .orElseThrow(() -> new PolicyValidationException("No parent policy version"));
        return new PolicyDraft(
                parent.getEffectiveFrom(),
                ruleRepository.findByVersionIdOrderByPriorityAsc(parent.getId()).stream()
                        .map(this::cloneRule).toList(),
                parameterRepository.findByVersionId(parent.getId()).stream()
                        .map(this::cloneParameter).toList()
        );
    }

    private Optional<PolicyVersion> parentVersion(LocalDate effectiveFrom) {
        List<PolicyVersion> versions = versionRepository.findAll().stream()
                .filter(v -> v.getActivatedAt() != null)
                .sorted(Comparator.comparing(PolicyVersion::getEffectiveFrom)
                        .thenComparing(PolicyVersion::getApprovedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PolicyVersion::getId))
                .toList();
        PolicyVersion before = null;
        for (PolicyVersion version : versions) {
            if (version.getEffectiveFrom().isBefore(effectiveFrom)) {
                before = version;
            }
        }
        if (before != null) {
            return Optional.of(before);
        }
        return versions.stream()
                .filter(v -> !v.getEffectiveFrom().isAfter(effectiveFrom))
                .reduce((first, second) -> second);
    }

    private void retireOlder(PolicyVersion parent, PolicyVersion current) {
        if ("ACTIVE".equals(parent.getStatus())) {
            parent.setStatus("RETIRED");
            versionRepository.save(parent);
        }
    }

    private String nextLabel(PolicyAmendment amendment, PolicyVersion parent) {
        String base = (parent == null ? "v5.0" : parent.getLabel()) + "-A" + amendment.getId();
        if (versionRepository.findByLabel(base).isEmpty()) {
            return base;
        }
        return base + "-" + System.currentTimeMillis();
    }

    private String activationStatus(LocalDate effectiveFrom) {
        return effectiveFrom.isAfter(LocalDate.now()) ? "SCHEDULED" : "ACTIVE";
    }

    private PolicyRule cloneRule(PolicyRule source) {
        PolicyRule rule = new PolicyRule();
        rule.setId(null);
        rule.setVersionId(source.getVersionId());
        rule.setRuleKey(source.getRuleKey());
        rule.setPriority(source.getPriority());
        rule.setClauseRef(source.getClauseRef());
        rule.setName(source.getName());
        rule.setScopeJson(source.getScopeJson());
        rule.setConditionJson(source.getConditionJson());
        rule.setOutcomeJson(source.getOutcomeJson());
        rule.setEnabled(source.getEnabled());
        return rule;
    }

    private PolicyParameter cloneParameter(PolicyParameter source) {
        PolicyParameter parameter = new PolicyParameter();
        parameter.setId(null);
        parameter.setVersionId(source.getVersionId());
        parameter.setParamKey(source.getParamKey());
        parameter.setValueJson(source.getValueJson());
        parameter.setValueType(source.getValueType());
        parameter.setScopeJson(source.getScopeJson());
        return parameter;
    }

    private String mergeScope(String scopeJson, String coreConditionJson) {
        if (scopeJson == null || ScopeJson.parse(scopeJson).isEmpty()) {
            return coreConditionJson;
        }
        Map<String, String> scope = ScopeJson.parse(scopeJson);
        List<Object> leaves = new ArrayList<>();
        for (String key : List.of("item_code", "category", "plant", "item_type")) {
            if (scope.containsKey(key)) {
                Map<String, Object> leaf = new LinkedHashMap<>();
                leaf.put("fact", key);
                leaf.put("op", "eq");
                leaf.put("value", scope.get(key));
                leaves.add(leaf);
            }
        }
        JsonNode core = PolicyJson.parse(coreConditionJson);
        List<Object> all = new ArrayList<>(leaves);
        if (core != null && core.has("all") && core.get("all").isArray() && core.size() == 1) {
            core.get("all").forEach(node -> all.add(PolicyJson.MAPPER.convertValue(node, Object.class)));
        } else if (core != null && !core.isNull()) {
            all.add(PolicyJson.MAPPER.convertValue(core, Object.class));
        }
        return write(Map.of("all", all));
    }

    private String stripLeadingScope(String conditionJson, String oldScopeJson) {
        int count = oldScopeJson == null ? 0 : ScopeJson.parse(oldScopeJson).size();
        if (count == 0) {
            return conditionJson;
        }
        JsonNode node = PolicyJson.parse(conditionJson);
        if (node == null || !node.has("all") || !node.get("all").isArray()) {
            return conditionJson;
        }
        List<Object> remaining = new ArrayList<>();
        int index = 0;
        for (JsonNode child : node.get("all")) {
            if (index++ >= count) {
                remaining.add(PolicyJson.MAPPER.convertValue(child, Object.class));
            }
        }
        if (remaining.size() == 1) {
            return write(remaining.get(0));
        }
        return write(Map.of("all", remaining));
    }

    private String canonicalScope(Object value, Set<String> allowed) {
        if (value == null) {
            return null;
        }
        Map<String, Object> scope = value instanceof String string ? readMap(string)
                : value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map)
                : null;
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        for (String key : scope.keySet()) {
            if (!allowed.contains(key)) {
                throw new PolicyValidationException("Invalid scope key '" + key + "'");
            }
        }
        return ScopeJson.canonical(scope);
    }

    private String inferType(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return value instanceof Integer || value instanceof Long ? "integer" : "decimal";
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
                return "boolean";
            }
            if (trimmed.matches("-?\\d+")) {
                return "integer";
            }
            if (trimmed.matches("-?\\d+(\\.\\d+)")) {
                return "decimal";
            }
        }
        return "string";
    }

    private String jsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")
                    || trimmed.equals("true") || trimmed.equals("false")
                    || trimmed.matches("-?\\d+(\\.\\d+)?")) {
                return trimmed;
            }
            return PolicyJson.write(string);
        }
        return PolicyJson.write(value);
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (value instanceof String string) {
            return readMap(string);
        }
        return null;
    }

    private List<Map<String, Object>> readOperations(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new PolicyValidationException("Invalid operations JSON");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PolicyValidationException("Cannot serialize JSON");
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
