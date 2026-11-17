package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.ContractCandidate;
import com.corvo.procurement.engine.model.ContractInfo;
import com.corvo.procurement.engine.model.DecisionRecord;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.LineResult;
import com.corvo.procurement.engine.model.PoGroup;
import com.corvo.procurement.engine.model.PolicyOutcome;
import com.corvo.procurement.engine.model.PolicyRuleInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.corvo.procurement.engine.model.RequirementRecord;
import com.corvo.procurement.policy.ConditionEvaluationException;
import com.corvo.procurement.policy.ConditionEvaluator;
import com.corvo.procurement.policy.FactResolver;
import com.corvo.procurement.policy.ParameterResolver;
import com.corvo.procurement.policy.PolicyJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DecisionEngine {
    private static final int DEFAULT_CLUB_WINDOW_DAYS = 14;
    private static final int DEFAULT_NEED_BY_WINDOW_DAYS = 7;
    private static final int DEFAULT_CONSOLIDATION_HOURS = 24;

    private final EngineData data;
    private final Set<String> plantCodes;
    private final ItemMatcher itemMatcher;
    private final PreferredSupplierEngine preferredSupplierEngine;

    public DecisionEngine(EngineData data) {
        this.data = data;
        this.plantCodes = new LinkedHashSet<>();
        data.plants().forEach(p -> plantCodes.add(p.code()));
        this.itemMatcher = new ItemMatcher(new ArrayList<>(data.items().values()), data.aliases());
        this.preferredSupplierEngine = new PreferredSupplierEngine(data);
    }

    public EngineResult run(List<PrLineInput> inputLines) {
        List<WorkLine> workLines = new ArrayList<>();
        for (int i = 0; i < inputLines.size(); i++) {
            WorkLine workLine = validateAndResolve(inputLines.get(i), i);
            workLines.add(workLine);
        }

        List<RequirementWork> requirements = club(workLines);
        ContractEvaluator contractEvaluator = new ContractEvaluator(data);

        List<RequirementWork> processingOrder = requirements.stream()
                .sorted(Comparator
                        .comparing(RequirementWork::requirementDate)
                        .thenComparing(RequirementWork::firstCreatedAt)
                        .thenComparing(r -> r.firstLine().input().prNo())
                        .thenComparing(r -> r.firstLine().input().prLine()))
                .toList();

        for (RequirementWork requirement : processingOrder) {
            processRequirement(requirement, contractEvaluator);
        }

        Map<Long, String> poByRequirement = consolidate(requirements);

        List<DecisionRecord> finalDecisions = new ArrayList<>();
        for (RequirementWork requirement : requirements) {
            DecisionRecord decision = requirement.decision;
            if (decision != null) {
                String poId = poByRequirement.get(requirement.id);
                decision = new DecisionRecord(
                        decision.requirementId(),
                        decision.decision(),
                        decision.route(),
                        decision.supplierId(),
                        decision.unitPrice(),
                        decision.groupValueInr(),
                        decision.suggestedSupplierIds(),
                        decision.policyVersion(),
                        decision.clause(),
                        decision.sourceReference(),
                        decision.reason(),
                        decision.traceJson(),
                        poId,
                        decision.contractId(),
                        decision.requiresCapexApproval(),
                        decision.preferredSuppliers()
                );
                requirement.decision = decision;
            }
            finalDecisions.add(decision);
        }
        finalDecisions.removeIf(java.util.Objects::isNull);

        List<LineResult> lineResults = buildLineResults(inputLines, workLines);
        List<RequirementRecord> requirementRecords = requirements.stream()
                .sorted(Comparator.comparingLong(RequirementWork::id))
                .map(RequirementWork::toRecord)
                .toList();
        List<PoGroup> poGroups = buildPoGroups(requirements, poByRequirement);

        return new EngineResult(lineResults, requirementRecords, finalDecisions, poGroups);
    }

    private WorkLine validateAndResolve(PrLineInput input, int order) {
        WorkLine line = new WorkLine(input, order);
        line.policy = selectPolicy(input.createdAt() == null ? null : input.createdAt().toLocalDate());
        String code = trimToNull(input.itemCode());
        ItemInfo codeItem = code == null ? null : data.items().get(code);
        line.rawCodeValid = codeItem != null;

        if (input.createdAt() == null) {
            return line.invalid("missing creation date", codeItem);
        }
        if (input.plant() == null || !plantCodes.contains(input.plant())) {
            return line.invalid("unknown plant", codeItem);
        }
        if (input.quantity() == null || input.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            return line.invalid("invalid quantity", codeItem);
        }
        if (input.needByDate() == null) {
            return line.invalid("missing need-by date", codeItem);
        }
        if (codeItem != null) {
            line.item = codeItem;
            line.resolvedItemCode = codeItem.itemCode();
            line.matchScore = BigDecimal.ONE;
            line.matchMethod = "code";
        } else {
            String description = input.description();
            if (code != null) {
                line.matchMethodPrefix = "invalid code " + code + "; ";
            }
            MatchParameters matchParameters = matchParameters(line.policy, input);
            ItemMatcher.MatchResult match = itemMatcher.match(
                    description,
                    matchParameters.threshold(),
                    matchParameters.tokenWeight(),
                    matchParameters.fuzzyWeight()
            );
            line.matchScore = match.score();
            if (!"NEW".equals(match.itemCode())) {
                line.item = data.items().get(match.itemCode());
                line.resolvedItemCode = match.itemCode();
                line.matchMethod = (line.matchMethodPrefix == null ? "" : line.matchMethodPrefix) + "description";
            } else {
                line.newItem = true;
                line.resolvedItemCode = "NEW";
                line.matchMethod = (line.matchMethodPrefix == null ? "" : line.matchMethodPrefix)
                        + (match.score().compareTo(matchParameters.threshold()) < 0 ? "below-threshold" : "no-description");
            }
        }
        if (line.item != null && input.uom() != null && !input.uom().isBlank()
                && !line.item.uom().equalsIgnoreCase(input.uom().trim())) {
            return line.invalid("UOM mismatch — manual review", line.item);
        }
        line.validationStatus = line.newItem ? "VALID_NEW_ITEM" : "VALID";
        return line;
    }

    private MatchParameters matchParameters(PolicySnapshot policy, PrLineInput input) {
        if (policy == null) {
            return new MatchParameters(new BigDecimal("0.50"), new BigDecimal("0.50"), new BigDecimal("0.50"));
        }
        RequirementFacts facts = new RequirementFacts(
                null,
                null,
                null,
                input.plant(),
                null,
                null,
                input.quantity(),
                input.uom(),
                null,
                false,
                false,
                false,
                false
        );
        ParameterResolver resolver = new ParameterResolver(policy.parameters());
        return new MatchParameters(
                resolver.resolveDecimal("item_match_threshold", facts),
                resolver.resolveDecimal("item_match_token_weight", facts),
                resolver.resolveDecimal("item_match_fuzzy_weight", facts)
        );
    }

    private List<RequirementWork> club(List<WorkLine> workLines) {
        List<WorkLine> valid = workLines.stream()
                .filter(line -> line.validationStatus != null && line.validationStatus.startsWith("VALID"))
                .sorted(Comparator
                        .comparing((WorkLine l) -> l.input.createdAt())
                        .thenComparing(l -> l.input.prNo())
                        .thenComparing(l -> l.input.prLine()))
                .toList();
        List<RequirementWork> requirements = new ArrayList<>();
        long nextId = 1;
        for (WorkLine line : valid) {
            RequirementWork target = null;
            if (!line.newItem) {
                for (RequirementWork requirement : requirements) {
                    if (requirement.newItem || !requirement.itemCode.equals(line.resolvedItemCode)
                            || !requirement.plant.equals(line.input.plant())) {
                        continue;
                    }
                    PolicySnapshot policyForWindow = requirement.policy != null ? requirement.policy : line.policy;
                    int clubWindow = policyForWindow == null
                            ? DEFAULT_CLUB_WINDOW_DAYS
                            : new ParameterResolver(policyForWindow.parameters())
                            .resolveInt("club_window_days", baseFacts(requirement, policyForWindow));
                    int needByWindow = policyForWindow == null
                            ? DEFAULT_NEED_BY_WINDOW_DAYS
                            : new ParameterResolver(policyForWindow.parameters())
                            .resolveInt("need_by_window_days", baseFacts(requirement, policyForWindow));
                    long clubDays = Math.abs(ChronoUnit.DAYS.between(
                            requirement.requirementDate, line.input.createdAt().toLocalDate()));
                    long needByDays = Math.abs(ChronoUnit.DAYS.between(
                            requirement.firstNeedBy, line.input.needByDate()));
                    if (clubDays <= clubWindow && needByDays <= needByWindow) {
                        target = requirement;
                        break;
                    }
                }
            }
            if (target == null) {
                RequirementWork requirement = new RequirementWork(nextId++);
                requirement.itemCode = line.resolvedItemCode;
                requirement.plant = line.input.plant();
                requirement.urgency = "Normal";
                requirement.requirementDate = line.input.createdAt().toLocalDate();
                requirement.firstCreatedAt = line.input.createdAt();
                requirement.firstNeedBy = line.input.needByDate();
                requirement.newItem = line.newItem;
                requirement.item = line.item;
                requirement.policy = selectPolicy(requirement.requirementDate);
                requirement.lines.add(line);
                line.requirement = requirement;
                requirements.add(requirement);
            } else {
                target.lines.add(line);
                line.requirement = target;
                if ("Emergency".equalsIgnoreCase(line.input.urgency())) {
                    target.urgency = "Emergency";
                }
                if (line.policy != null && target.policy == null) {
                    target.policy = line.policy;
                }
            }
            if ("Emergency".equalsIgnoreCase(line.input.urgency())) {
                line.requirement.urgency = "Emergency";
            }
        }
        return requirements;
    }

    private void processRequirement(RequirementWork requirement, ContractEvaluator contractEvaluator) {
        if (requirement.policy == null) {
            requirement.decision = notProcessed(requirement, "no policy effective for requirement date", null);
            return;
        }
        if (requirement.newItem) {
            processNewItem(requirement);
            return;
        }
        ItemInfo item = data.items().get(requirement.itemCode);
        if (item == null) {
            requirement.decision = notProcessed(requirement, "item could not be resolved", requirement.policy.label());
            return;
        }
        requirement.item = item;
        PolicySnapshot policy = requirement.policy;
        ParameterResolver resolver = new ParameterResolver(policy.parameters());
        BigDecimal totalQuantity = requirement.lines.stream()
                .map(l -> l.input.quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RequirementFacts baseFacts = new RequirementFacts(
                item.itemCode(),
                item.itemType(),
                item.category(),
                requirement.plant,
                null,
                requirement.urgency,
                totalQuantity,
                item.uom(),
                (int) ChronoUnit.DAYS.between(requirement.requirementDate, requirement.firstNeedBy),
                false,
                false,
                false,
                false
        );
        int valuationHistoryDays = resolver.resolveInt("valuation_history_days", baseFacts);
        int activeHistoryDays = resolver.resolveInt("active_history_days", baseFacts);

        List<ContractCandidate> applicable = contractEvaluator.applicableContracts(
                item.itemCode(), requirement.requirementDate, totalQuantity);
        boolean hasApplicableContract = !applicable.isEmpty();
        boolean hasContractWithCeiling = applicable.stream().anyMatch(ContractCandidate::usable);

        ContractEvaluator.ValuationResult valuation;
        if (item.isCapex()) {
            valuation = contractEvaluator.valueCapex(item, requirement.requirementDate, valuationHistoryDays);
        } else {
            valuation = contractEvaluator.valueNonCapex(item, requirement.requirementDate, totalQuantity, valuationHistoryDays);
        }
        BigDecimal value = valuation.unitPrice() == null ? null
                : totalQuantity.multiply(valuation.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        boolean hasActiveHistory = contractEvaluator.hasActiveHistory(item.itemCode(), requirement.requirementDate, activeHistoryDays);

        RequirementFacts facts = new RequirementFacts(
                item.itemCode(),
                item.itemType(),
                item.category(),
                requirement.plant,
                value,
                requirement.urgency,
                totalQuantity,
                item.uom(),
                (int) ChronoUnit.DAYS.between(requirement.requirementDate, requirement.firstNeedBy),
                hasApplicableContract,
                hasContractWithCeiling,
                hasActiveHistory,
                false
        );

        requirement.unitPrice = valuation.unitPrice();
        requirement.value = value;
        requirement.valuationSource = valuation.source();
        requirement.usedContractId = valuation.contractId();

        List<PreferredSupplier> preferred = preferredSupplierEngine.compute(item, requirement.requirementDate, policy, resolver);
        FactResolver factResolver = new FactResolver(policy, resolver);
        Map<String, Object> factValues = factResolver.resolve(facts);
        ConditionEvaluator evaluator = new ConditionEvaluator(policy, resolver);

        PolicyRuleInfo matched = null;
        for (PolicyRuleInfo rule : policy.rules().stream().sorted(Comparator.comparing(PolicyRuleInfo::priority)).toList()) {
            if (Boolean.FALSE.equals(rule.enabled())) {
                continue;
            }
            try {
                if (evaluator.evaluate(rule.conditionJson(), factValues, facts)) {
                    matched = rule;
                    break;
                }
            } catch (ConditionEvaluationException e) {
                throw new ConditionEvaluationException("Rule " + rule.ruleKey() + ": " + e.getMessage());
            }
        }
        if (matched == null) {
            requirement.decision = notProcessed(requirement, "no policy rule matched", policy.label());
            return;
        }
        PolicyOutcome outcome = parseOutcome(matched.outcomeJson());
        String route = outcome.route();
        String reason;
        String sourceReference = null;
        BigDecimal decisionUnitPrice = null;
        String supplierId = null;
        String contractId = valuation.contractId();
        List<String> suggestions = List.of();

        if ("CONTRACT_PO".equals(route)) {
            ContractCandidate selected = applicable.stream()
                    .filter(ContractCandidate::usable)
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                requirement.decision = notProcessed(requirement, "no usable contract for CONTRACT_PO", policy.label());
                return;
            }
            supplierId = selected.contract().supplierId();
            decisionUnitPrice = selected.line().rate();
            sourceReference = "contract " + selected.contract().contractId() + " at " + selected.line().rate();
            contractId = selected.contract().contractId();
            reason = "Clause " + matched.clauseRef() + " (" + policy.label() + "): CONTRACT_PO from "
                    + supplierId + " using " + selected.contract().contractId() + " at ₹"
                    + selected.line().rate() + "; valuation source " + valuation.source();
            contractEvaluator.consume(contractId, value);
        } else if ("EMERGENCY_PO".equals(route) || "SPOT_PO".equals(route)) {
            PreferredSupplier first = preferred.isEmpty() ? null : preferred.get(0);
            if (first == null) {
                requirement.decision = notProcessed(requirement, "no preferred supplier available", policy.label());
                return;
            }
            supplierId = first.supplierId();
            decisionUnitPrice = first.referencePrice();
            sourceReference = first.referenceSource();
            reason = "Clause " + matched.clauseRef() + " (" + policy.label() + "): " + route + " from 1st preferred supplier "
                    + supplierId + " at reference price ₹" + first.referencePrice()
                    + "; valuation source " + valuation.source();
        } else if ("RFQ".equals(route) || "RFP".equals(route) || "CAPEX_SOURCING".equals(route)) {
            int count = outcome.suggestionCount() == null
                    ? resolver.resolveInt("RFQ".equals(route) ? "suggestion_count_rfq" : "RFP".equals(route) ? "suggestion_count_rfp" : "suggestion_count_capex", facts)
                    : outcome.suggestionCount();
            suggestions = preferredSupplierEngine.suggestions(item, requirement.requirementDate, count, policy, resolver);
            sourceReference = "preferred supplier ranking";
            reason = "Clause " + matched.clauseRef() + " (" + policy.label() + "): " + route
                    + " with suggestions " + String.join(";", suggestions)
                    + "; valuation source " + valuation.source();
        } else if ("NEW_ITEM_SOURCING".equals(route)) {
            reason = "Clause " + matched.clauseRef() + " (" + policy.label() + "): NEW_ITEM_SOURCING";
            sourceReference = "new item";
        } else {
            requirement.decision = notProcessed(requirement, "unsupported route " + route, policy.label());
            return;
        }

        String decision = outcome.decision();
        if (decision == null) {
            decision = "CONTRACT_PO".equals(route) || "EMERGENCY_PO".equals(route) || "SPOT_PO".equals(route)
                    ? "PO" : "SOURCING_REQUEST";
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requirementId", requirement.id);
        trace.put("itemCode", item.itemCode());
        trace.put("valueInr", value);
        trace.put("valuationSource", valuation.source());
        trace.put("facts", factValues);
        trace.put("matchedRule", matched.ruleKey());
        trace.put("route", route);
        String consumedContractId = contractId;
        BigDecimal consumedValue = value;
        trace.put("applicableContracts", applicable.stream().map(c -> {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("contractId", c.contract().contractId());
            contract.put("supplierId", c.contract().supplierId());
            contract.put("rate", c.line().rate());
            contract.put("usable", c.usable());
            contract.put("valueCap", c.contract().valueCap());
            BigDecimal consumedAfter = contractEvaluator.consumedFor(c.contract().contractId());
            BigDecimal consumedBefore = consumedAfter;
            if ("CONTRACT_PO".equals(route) && c.contract().contractId().equals(consumedContractId) && consumedValue != null) {
                consumedBefore = consumedAfter.subtract(consumedValue);
            }
            contract.put("consumedBefore", consumedBefore);
            contract.put("consumedAfter", consumedAfter);
            contract.put("prospectiveValue", c.prospectiveValue());
            contract.put("remainingAfter", c.contract().valueCap() == null ? null
                    : c.contract().valueCap().subtract(consumedAfter));
            return contract;
        }).toList());
        trace.put("preferredSuppliers", preferred.stream().map(PreferredSupplier::supplierId).toList());
        trace.put("suggestions", suggestions);
        requirement.decision = new DecisionRecord(
                requirement.id,
                decision,
                route,
                supplierId,
                decisionUnitPrice,
                value,
                suggestions,
                policy.label(),
                matched.clauseRef(),
                sourceReference,
                reason,
                PolicyJson.write(trace),
                null,
                contractId,
                outcome.requiresCapexApproval(),
                preferred
        );
    }

    private void processNewItem(RequirementWork requirement) {
        BigDecimal quantity = requirement.lines.stream()
                .map(l -> l.input.quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimate = requirement.lines.get(0).input.estimatedUnitPrice();
        BigDecimal value = estimate == null ? null : quantity.multiply(estimate).setScale(2, RoundingMode.HALF_UP);
        requirement.value = value;
        requirement.unitPrice = estimate;
        requirement.valuationSource = estimate == null ? "no estimate provided — value not calculable" : "requester estimate";
        String policyLabel = requirement.policy == null ? "" : requirement.policy.label();
        String reason = "Clause 1.2 (" + (policyLabel.isBlank() ? "no policy" : policyLabel)
                + "): NEW_ITEM_SOURCING; " + requirement.valuationSource;
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requirementId", requirement.id);
        trace.put("newItem", true);
        trace.put("valueInr", value);
        trace.put("valuationSource", requirement.valuationSource);
        requirement.decision = new DecisionRecord(
                requirement.id,
                "SOURCING_REQUEST",
                "NEW_ITEM_SOURCING",
                null,
                null,
                value,
                List.of(),
                policyLabel.isBlank() ? null : policyLabel,
                "1.2",
                "new item",
                reason,
                PolicyJson.write(trace),
                null,
                null,
                false,
                List.of()
        );
    }

    private DecisionRecord notProcessed(RequirementWork requirement, String reason, String policyLabel) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requirementId", requirement.id);
        trace.put("notProcessed", reason);
        if (requirement.itemCode != null) {
            trace.put("itemCode", requirement.itemCode);
        }
        return new DecisionRecord(
                requirement.id,
                "",
                "NOT_PROCESSED",
                null,
                null,
                null,
                List.of(),
                policyLabel,
                null,
                null,
                reason,
                PolicyJson.write(trace),
                null,
                null,
                false,
                List.of()
        );
    }

    private Map<Long, String> consolidate(List<RequirementWork> requirements) {
        List<RequirementWork> poRequirements = requirements.stream()
                .filter(r -> r.decision != null)
                .filter(r -> "PO".equals(r.decision.decision()))
                .sorted(Comparator
                        .comparing(RequirementWork::firstCreatedAt)
                        .thenComparing(r -> r.firstLine().input().prNo())
                        .thenComparing(r -> r.firstLine().input().prLine()))
                .toList();
        List<PoWork> pos = new ArrayList<>();
        Map<Long, String> poByRequirement = new LinkedHashMap<>();
        int sequence = 1;
        for (RequirementWork requirement : poRequirements) {
            int consolidationHours = consolidationHours(requirement);
            PoWork target = null;
            for (PoWork po : pos) {
                if (!po.supplierId.equals(requirement.decision.supplierId())
                        || !po.plant.equals(requirement.plant)
                        || !po.route.equals(requirement.decision.route())) {
                    continue;
                }
                LocalDateTime windowEnd = po.firstRequirementAt.plusHours(consolidationHours);
                if (!requirement.firstCreatedAt.isBefore(po.firstRequirementAt)
                        && !requirement.firstCreatedAt.isAfter(windowEnd)) {
                    target = po;
                    break;
                }
            }
            if (target == null) {
                target = new PoWork(String.format("PO-%04d", sequence++),
                        requirement.decision.supplierId(), requirement.plant,
                        requirement.decision.route(), requirement.firstCreatedAt);
                pos.add(target);
            }
            target.requirementIds.add(requirement.id);
            poByRequirement.put(requirement.id, target.poId);
        }
        return poByRequirement;
    }

    private List<PoGroup> buildPoGroups(List<RequirementWork> requirements, Map<Long, String> poByRequirement) {
        Map<String, PoGroup> groups = new LinkedHashMap<>();
        for (RequirementWork requirement : requirements) {
            String poId = poByRequirement.get(requirement.id);
            if (poId == null) {
                continue;
            }
            groups.computeIfAbsent(poId, id -> new PoGroup(
                    id,
                    requirement.decision.supplierId(),
                    requirement.plant,
                    requirement.decision.route(),
                    requirement.firstCreatedAt,
                    new ArrayList<>()
            )).requirementIds().add(requirement.id);
        }
        return new ArrayList<>(groups.values());
    }

    private List<LineResult> buildLineResults(List<PrLineInput> inputLines, List<WorkLine> workLines) {
        Map<PrLineInput, WorkLine> byInput = new IdentityHashMap<>();
        for (WorkLine workLine : workLines) {
            byInput.put(workLine.input, workLine);
        }
        List<LineResult> results = new ArrayList<>();
        for (PrLineInput input : inputLines) {
            WorkLine line = byInput.get(input);
            if (line == null) {
                results.add(notProcessedLine(input, "unprocessed line", null));
                continue;
            }
            if (line.validationStatus == null || !line.validationStatus.startsWith("VALID")) {
                String policyLabel = line.policy == null ? null : line.policy.label();
                results.add(notProcessedLine(input, line.validationReason, policyLabel));
                continue;
            }
            RequirementWork requirement = line.requirement;
            if (requirement == null || requirement.decision == null) {
                results.add(notProcessedLine(input, "no decision produced", null));
                continue;
            }
            DecisionRecord decision = requirement.decision;
            if ("NOT_PROCESSED".equals(decision.route())) {
                String resolved = line.rawCodeValid ? trimToNull(input.itemCode()) : "";
                results.add(new LineResult(
                        input.id(), input.prNo(), input.prLine(), resolved, null, "",
                        "", "NOT_PROCESSED", "", null, null, "",
                        decision.policyVersion() == null ? "" : decision.policyVersion(),
                        decision.reason(), line.matchScore, line.matchMethod, "NOT_PROCESSED"
                ));
                continue;
            }
            results.add(new LineResult(
                    input.id(),
                    input.prNo(),
                    input.prLine(),
                    line.resolvedItemCode,
                    requirement.id,
                    decision.poId() == null ? "" : decision.poId(),
                    decision.decision() == null ? "" : decision.decision(),
                    decision.route(),
                    decision.supplierId() == null ? "" : decision.supplierId(),
                    decision.unitPrice(),
                    decision.groupValueInr(),
                    decision.suggestedSupplierIds().isEmpty() ? "" : String.join(";", decision.suggestedSupplierIds()),
                    decision.policyVersion() == null ? "" : decision.policyVersion(),
                    decision.reason(),
                    line.matchScore,
                    line.matchMethod,
                    "DECIDED"
            ));
        }
        return results;
    }

    private LineResult notProcessedLine(PrLineInput input, String reason, String policyLabel) {
        String resolved = "";
        if (input.itemCode() != null && data.items().containsKey(input.itemCode())) {
            resolved = input.itemCode();
        }
        return new LineResult(
                input.id(), input.prNo(), input.prLine(), resolved, null, "",
                "", "NOT_PROCESSED", "", null, null, "",
                policyLabel == null ? "" : policyLabel,
                reason, null, null, "NOT_PROCESSED"
        );
    }

    private int consolidationHours(RequirementWork requirement) {
        PolicySnapshot policy = requirement.policy;
        if (policy == null) {
            return DEFAULT_CONSOLIDATION_HOURS;
        }
        return new ParameterResolver(policy.parameters())
                .resolveInt("consolidation_hours", baseFacts(requirement, policy));
    }

    private RequirementFacts baseFacts(RequirementWork requirement, PolicySnapshot policy) {
        return new RequirementFacts(
                requirement.itemCode,
                requirement.item == null ? null : requirement.item.itemType(),
                requirement.item == null ? null : requirement.item.category(),
                requirement.plant,
                requirement.value,
                requirement.urgency,
                null,
                requirement.item == null ? null : requirement.item.uom(),
                null,
                false,
                false,
                false,
                requirement.newItem
        );
    }

    private PolicyOutcome parseOutcome(String outcomeJson) {
        JsonNode node = PolicyJson.parse(outcomeJson);
        return new PolicyOutcome(
                node.hasNonNull("decision") ? node.get("decision").asText() : null,
                node.hasNonNull("route") ? node.get("route").asText() : null,
                node.hasNonNull("supplier_strategy") ? node.get("supplier_strategy").asText() : null,
                node.hasNonNull("suggestion_count") ? node.get("suggestion_count").asInt() : null,
                node.hasNonNull("requires_capex_approval") && node.get("requires_capex_approval").asBoolean()
        );
    }

    private PolicySnapshot selectPolicy(LocalDate date) {
        return PolicySelector.select(data.policies(), date).orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record MatchParameters(BigDecimal threshold, BigDecimal tokenWeight, BigDecimal fuzzyWeight) {
    }

    private static final class WorkLine {
        private final PrLineInput input;
        private final int order;
        private PolicySnapshot policy;
        private ItemInfo item;
        private String resolvedItemCode;
        private boolean newItem;
        private boolean rawCodeValid;
        private BigDecimal matchScore;
        private String matchMethod;
        private String matchMethodPrefix;
        private String validationStatus;
        private String validationReason;
        private RequirementWork requirement;

        private WorkLine(PrLineInput input, int order) {
            this.input = input;
            this.order = order;
        }

        private PrLineInput input() {
            return input;
        }

        private WorkLine invalid(String reason, ItemInfo codeItem) {
            this.validationStatus = "NOT_PROCESSED";
            this.validationReason = reason;
            if (codeItem != null) {
                this.item = codeItem;
                this.resolvedItemCode = codeItem.itemCode();
            }
            return this;
        }
    }

    private static final class RequirementWork {
        private final long id;
        private String itemCode;
        private String plant;
        private String urgency;
        private LocalDate requirementDate;
        private LocalDateTime firstCreatedAt;
        private LocalDate firstNeedBy;
        private boolean newItem;
        private ItemInfo item;
        private PolicySnapshot policy;
        private final List<WorkLine> lines = new ArrayList<>();
        private BigDecimal unitPrice;
        private BigDecimal value;
        private String valuationSource;
        private String usedContractId;
        private DecisionRecord decision;

        private RequirementWork(long id) {
            this.id = id;
        }

        private long id() {
            return id;
        }

        private LocalDate requirementDate() {
            return requirementDate;
        }

        private LocalDateTime firstCreatedAt() {
            return firstCreatedAt;
        }

        private WorkLine firstLine() {
            return lines.get(0);
        }

        private RequirementRecord toRecord() {
            return new RequirementRecord(
                    id,
                    itemCode,
                    plant,
                    urgency,
                    requirementDate,
                    firstCreatedAt,
                    firstNeedBy,
                    newItem,
                    lines.stream().map(l -> l.input).toList(),
                    value,
                    valuationSource,
                    usedContractId,
                    unitPrice
            );
        }
    }

    private static final class PoWork {
        private final String poId;
        private final String supplierId;
        private final String plant;
        private final String route;
        private final LocalDateTime firstRequirementAt;
        private final List<Long> requirementIds = new ArrayList<>();

        private PoWork(String poId, String supplierId, String plant, String route, LocalDateTime firstRequirementAt) {
            this.poId = poId;
            this.supplierId = supplierId;
            this.plant = plant;
            this.route = route;
            this.firstRequirementAt = firstRequirementAt;
        }
    }
}
