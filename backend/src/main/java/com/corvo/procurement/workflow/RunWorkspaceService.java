package com.corvo.procurement.workflow;

import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PoLine;
import com.corvo.procurement.domain.PoLineRepository;
import com.corvo.procurement.domain.PrLine;
import com.corvo.procurement.domain.PrLineRepository;
import com.corvo.procurement.domain.PurchaseOrder;
import com.corvo.procurement.domain.PurchaseOrderRepository;
import com.corvo.procurement.domain.Requirement;
import com.corvo.procurement.domain.RequirementLine;
import com.corvo.procurement.domain.RequirementLineRepository;
import com.corvo.procurement.domain.RequirementRepository;
import com.corvo.procurement.domain.SourcingEvent;
import com.corvo.procurement.domain.SourcingEventRepository;
import com.corvo.procurement.engine.model.DecisionRecord;
import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.LineResult;
import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.engine.model.RequirementRecord;
import com.corvo.procurement.ingestion.BatchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RunWorkspaceService {
    private final BatchService batchService;
    private final RequirementRepository requirementRepository;
    private final RequirementLineRepository requirementLineRepository;
    private final PrLineRepository prLineRepository;
    private final DecisionRepository decisionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoLineRepository poLineRepository;
    private final SourcingEventRepository sourcingEventRepository;
    private final ObjectMapper objectMapper;

    public RunWorkspaceService(BatchService batchService,
                               RequirementRepository requirementRepository,
                               RequirementLineRepository requirementLineRepository,
                               PrLineRepository prLineRepository,
                               DecisionRepository decisionRepository,
                               PurchaseOrderRepository purchaseOrderRepository,
                               PoLineRepository poLineRepository,
                               SourcingEventRepository sourcingEventRepository,
                               ObjectMapper objectMapper) {
        this.batchService = batchService;
        this.requirementRepository = requirementRepository;
        this.requirementLineRepository = requirementLineRepository;
        this.prLineRepository = prLineRepository;
        this.decisionRepository = decisionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poLineRepository = poLineRepository;
        this.sourcingEventRepository = sourcingEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> workspace(Long runId) {
        EngineResult result = batchService.result(runId);
        List<Requirement> storedRequirements = requirementRepository.findByRunId(runId).stream()
                .sorted(Comparator.comparing(Requirement::getRequirementDate)
                        .thenComparing(Requirement::getFirstCreatedAt)
                        .thenComparing(Requirement::getId))
                .toList();
        Map<Long, Requirement> dbById = storedRequirements.stream()
                .collect(Collectors.toMap(Requirement::getId, Function.identity(), (a, b) -> a));
        Map<Long, Decision> decisionByRequirement = decisionRepository.findByRunId(runId).stream()
                .collect(Collectors.toMap(Decision::getRequirementId, Function.identity(), (a, b) -> a));
        Map<Long, DecisionRecord> engineDecisionByRequirement = result.decisions().stream()
                .collect(Collectors.toMap(DecisionRecord::requirementId, Function.identity(), (a, b) -> a));
        Map<Long, LineResult> lineByPrLine = result.lines().stream()
                .filter(line -> line.prLineId() != null)
                .collect(Collectors.toMap(LineResult::prLineId, Function.identity(), (a, b) -> a));

        List<Long> prLineIds = storedRequirements.stream()
                .flatMap(requirement -> requirementLineRepository.findByRequirementId(requirement.getId()).stream())
                .map(RequirementLine::getPrLineId)
                .distinct()
                .toList();
        Map<Long, PrLine> prLineById = prLineIds.isEmpty()
                ? Map.of()
                : prLineRepository.findByIdIn(prLineIds).stream()
                .collect(Collectors.toMap(PrLine::getId, Function.identity(), (a, b) -> a));

        Map<Long, List<Map<String, Object>>> prLinesByRequirement = new HashMap<>();
        for (Requirement requirement : storedRequirements) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (RequirementLine link : requirementLineRepository.findByRequirementId(requirement.getId())) {
                PrLine prLine = prLineById.get(link.getPrLineId());
                if (prLine == null) {
                    continue;
                }
                LineResult lineResult = lineByPrLine.get(prLine.getId());
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("prLineId", prLine.getId());
                line.put("prNo", prLine.getPrNo());
                line.put("prLine", prLine.getPrLine());
                line.put("createdAt", prLine.getCreatedAt());
                line.put("requester", prLine.getRequester());
                line.put("department", prLine.getDepartment());
                line.put("plant", prLine.getPlant());
                line.put("rawItemCode", prLine.getRawItemCode());
                line.put("description", prLine.getRawDescription());
                line.put("quantity", prLine.getQuantity());
                line.put("uom", prLine.getRawUom());
                line.put("estimatedUnitPrice", prLine.getEstimatedUnitPrice());
                line.put("needByDate", prLine.getNeedByDate());
                line.put("urgency", prLine.getUrgency());
                line.put("resolvedItemCode", lineResult == null ? prLine.getResolvedItemCode() : lineResult.resolvedItemCode());
                line.put("matchMethod", lineResult == null ? prLine.getMatchMethod() : lineResult.matchMethod());
                line.put("matchScore", lineResult == null ? prLine.getMatchScore() : lineResult.matchScore());
                line.put("validationStatus", lineResult == null ? prLine.getValidationStatus() : lineResult.validationStatus());
                lines.add(line);
            }
            prLinesByRequirement.put(requirement.getId(), lines);
        }

        List<Map<String, Object>> requirements = new ArrayList<>();
        for (Requirement requirement : storedRequirements) {
            RequirementRecord engineRequirement = requirement.getEngineRequirementId() == null
                    ? null : result.requirements().stream()
                    .filter(r -> r.id() == requirement.getEngineRequirementId())
                    .findFirst().orElse(null);
            Decision decision = decisionByRequirement.get(requirement.getId());
            DecisionRecord engineDecision = engineRequirement == null
                    ? null : engineDecisionByRequirement.get(engineRequirement.id());
            List<Map<String, Object>> prLines = prLinesByRequirement.get(requirement.getId());
            Map<String, Object> trace = readMap(decision == null ? null : decision.getTraceJson());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", requirement.getId());
            body.put("engineRequirementId", requirement.getEngineRequirementId());
            body.put("itemCode", requirement.getItemCode());
            body.put("plant", requirement.getPlant());
            body.put("urgency", requirement.getUrgency());
            body.put("requirementDate", requirement.getRequirementDate());
            body.put("firstCreatedAt", requirement.getFirstCreatedAt());
            body.put("needByDate", engineRequirement == null ? null : engineRequirement.needByDate());
            body.put("ageDays", requirement.getRequirementDate() == null ? null
                    : Math.max(0, ChronoUnit.DAYS.between(requirement.getRequirementDate(), LocalDate.now())));
            body.put("quantity", prLines.stream()
                    .map(line -> decimal(line.get("quantity")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            body.put("value", requirement.getValue());
            body.put("unitPrice", requirement.getUnitPrice());
            body.put("valuationSource", requirement.getValuationSource());
            body.put("decisionId", decision == null ? null : decision.getId());
            body.put("decision", decision == null ? null : decision.getDecision());
            body.put("route", decision == null ? null : decision.getRoute());
            body.put("status", decision == null ? null : decision.getApprovalStatus());
            body.put("approvedBy", decision == null ? null : decision.getApprovedBy());
            body.put("approvedAt", decision == null ? null : decision.getApprovedAt());
            body.put("poId", decision == null ? null : decision.getPoId());
            body.put("supplierId", decision == null ? null : decision.getSupplierId());
            body.put("groupValueInr", decision == null ? requirement.getValue() : decision.getGroupValueInr());
            body.put("policyVersion", decision == null ? null : decision.getPolicyVersion());
            body.put("clause", decision == null ? null : decision.getClause());
            body.put("sourceReference", decision == null ? null : decision.getSourceReference());
            body.put("reason", decision == null ? null : decision.getReason());
            body.put("suggestions", engineDecision == null ? List.of() : engineDecision.suggestedSupplierIds());
            body.put("requiresCapexApproval", decision != null && Boolean.TRUE.equals(decision.getRequiresCapexApproval()));
            body.put("preferredSuppliers", engineDecision == null ? List.of()
                    : engineDecision.preferredSuppliers().stream().map(this::preferred).toList());
            body.put("applicableContracts", trace.getOrDefault("applicableContracts", List.of()));
            body.put("trace", trace);
            body.put("prLines", prLines);
            body.put("matchMethod", prLines.isEmpty() ? null : prLines.get(0).get("matchMethod"));
            body.put("matchScore", prLines.isEmpty() ? null : prLines.get(0).get("matchScore"));
            requirements.add(body);
        }

        List<Map<String, Object>> issues = result.lines().stream()
                .filter(line -> "NOT_PROCESSED".equals(line.route()))
                .map(line -> {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("prNo", line.prNo());
                    issue.put("prLine", line.prLine());
                    issue.put("itemCode", line.resolvedItemCode());
                    issue.put("validationStatus", line.validationStatus());
                    issue.put("reason", line.reason());
                    return issue;
                })
                .toList();

        List<Map<String, Object>> pos = new ArrayList<>();
        List<Long> requirementIds = storedRequirements.stream().map(Requirement::getId).toList();
        Map<String, List<Long>> requirementsByPo = new LinkedHashMap<>();
        List<PoLine> poLines = requirementIds.isEmpty() ? List.of()
                : poLineRepository.findByRequirementIdIn(requirementIds);
        poLines.stream()
                .sorted(Comparator.comparing(PoLine::getId))
                .forEach(line -> requirementsByPo
                        .computeIfAbsent(line.getPoId(), key -> new ArrayList<>())
                        .add(line.getRequirementId()));
        for (Map.Entry<String, List<Long>> entry : requirementsByPo.entrySet()) {
            PurchaseOrder storedPo = purchaseOrderRepository.findById(entry.getKey()).orElse(null);
            if (storedPo == null) {
                continue;
            }
            List<Map<String, Object>> poRequirements = entry.getValue().stream()
                    .distinct()
                    .map(dbById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(requirement -> {
                        Decision decision = decisionByRequirement.get(requirement.getId());
                        Map<String, Object> summary = new LinkedHashMap<>();
                        summary.put("id", requirement.getId());
                        summary.put("itemCode", requirement.getItemCode());
                        summary.put("plant", requirement.getPlant());
                        summary.put("value", requirement.getValue());
                        summary.put("route", decision == null ? storedPo.getRoute() : decision.getRoute());
                        summary.put("status", decision == null ? null : decision.getApprovalStatus());
                        summary.put("decisionId", decision == null ? null : decision.getId());
                        return summary;
                    })
                    .toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("poId", storedPo.getPoId());
            body.put("supplierId", storedPo.getSupplierId());
            body.put("plant", storedPo.getPlant());
            body.put("route", storedPo.getRoute());
            body.put("firstRequirementAt", storedPo.getFirstRequirementAt());
            body.put("status", storedPo.getApprovalStatus());
            body.put("approvedBy", storedPo.getApprovedBy());
            body.put("approvedAt", storedPo.getApprovedAt());
            body.put("requirementCount", poRequirements.size());
            body.put("value", poRequirements.stream()
                    .map(requirement -> decimal(requirement.get("value")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            body.put("requirements", poRequirements);
            pos.add(body);
        }

        List<SourcingEvent> sourcing = requirementIds.isEmpty() ? List.of()
                : sourcingEventRepository.findByRequirementIdIn(requirementIds);
        List<Map<String, Object>> sourcingRows = sourcing.stream()
                .sorted(Comparator.comparing(SourcingEvent::getId))
                .map(event -> {
                    Requirement requirement = dbById.get(event.getRequirementId());
                    Decision decision = requirement == null ? null : decisionByRequirement.get(requirement.getId());
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", event.getId());
                    body.put("route", event.getRoute());
                    body.put("requirementId", event.getRequirementId());
                    body.put("itemCode", requirement == null ? null : requirement.getItemCode());
                    body.put("plant", requirement == null ? null : requirement.getPlant());
                    body.put("value", requirement == null ? null : requirement.getValue());
                    body.put("suggestions", event.getSuggestedSupplierIds() == null || event.getSuggestedSupplierIds().isBlank()
                            ? List.of() : List.of(event.getSuggestedSupplierIds().split(";")));
                    body.put("status", event.getApprovalStatus());
                    body.put("decisionId", decision == null ? null : decision.getId());
                    body.put("requiresCapexApproval", decision != null && Boolean.TRUE.equals(decision.getRequiresCapexApproval()));
                    body.put("approvedBy", event.getApprovedBy());
                    body.put("approvedAt", event.getApprovedAt());
                    body.put("sentBy", event.getSentBy());
                    body.put("sentAt", event.getSentAt());
                    return body;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requirements", requirements);
        response.put("pos", pos);
        response.put("sourcing", sourcingRows);
        response.put("issues", issues);
        return response;
    }

    private Map<String, Object> preferred(PreferredSupplier supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rank", supplier.rank());
        body.put("supplierId", supplier.supplierId());
        body.put("score", supplier.score());
        body.put("scoreComponents", supplier.scoreComponents());
        body.put("referencePrice", supplier.referencePrice());
        body.put("referenceSource", supplier.referenceSource());
        body.put("hasContract", supplier.hasContract());
        body.put("poCount", supplier.poCount());
        body.put("recencyBucket", supplier.recencyBucket());
        return body;
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
