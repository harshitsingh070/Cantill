package com.corvo.procurement.audit;

import com.corvo.procurement.domain.AuditLog;
import com.corvo.procurement.domain.AuditLogRepository;
import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PoLine;
import com.corvo.procurement.domain.PoLineRepository;
import com.corvo.procurement.domain.PurchaseOrder;
import com.corvo.procurement.domain.PurchaseOrderRepository;
import com.corvo.procurement.domain.Requirement;
import com.corvo.procurement.domain.RequirementRepository;
import com.corvo.procurement.domain.SourcingEvent;
import com.corvo.procurement.domain.SourcingEventRepository;
import com.corvo.procurement.domain.Supplier;
import com.corvo.procurement.domain.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuditService {
    private static final Set<String> PO_ROUTES = Set.of("CONTRACT_PO", "EMERGENCY_PO", "SPOT_PO");
    private static final Set<String> ROUTES = Set.of(
            "CONTRACT_PO", "EMERGENCY_PO", "SPOT_PO", "RFQ", "RFP", "CAPEX_SOURCING",
            "NEW_ITEM_SOURCING", "NOT_PROCESSED"
    );

    private final AuditLogRepository auditLogRepository;
    private final DecisionRepository decisionRepository;
    private final SupplierRepository supplierRepository;
    private final RequirementRepository requirementRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoLineRepository poLineRepository;
    private final SourcingEventRepository sourcingEventRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository,
                        DecisionRepository decisionRepository,
                        SupplierRepository supplierRepository,
                        RequirementRepository requirementRepository,
                        PurchaseOrderRepository purchaseOrderRepository,
                        PoLineRepository poLineRepository,
                        SourcingEventRepository sourcingEventRepository,
                        ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.decisionRepository = decisionRepository;
        this.supplierRepository = supplierRepository;
        this.requirementRepository = requirementRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poLineRepository = poLineRepository;
        this.sourcingEventRepository = sourcingEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AuditLog> list() {
        return auditLogRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Decision> decisionsByRun(Long runId) {
        return decisionRepository.findByRunId(runId).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Transactional
    public Decision override(Long decisionId, Map<String, Object> body) {
        Decision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));
        String comment = string(body.get("comment"));
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("A written comment is required for an override");
        }
        String actor = body.get("actor") == null ? "buyer" : string(body.get("actor"));

        String requestedRoute = string(body.get("route"));
        if (requestedRoute != null && !ROUTES.contains(requestedRoute)) {
            throw new IllegalArgumentException("Unknown route: " + requestedRoute);
        }
        String targetRoute = requestedRoute == null ? decision.getRoute() : requestedRoute;
        boolean targetIsPo = PO_ROUTES.contains(targetRoute);
        String supplierId = body.containsKey("supplier_id")
                ? string(body.get("supplier_id")) : decision.getSupplierId();
        BigDecimal unitPrice = body.containsKey("unit_price")
                ? decimal(body.get("unit_price")) : decision.getUnitPrice();

        if (targetIsPo) {
            if (supplierId == null || supplierId.isBlank()) {
                throw new IllegalArgumentException("supplier_id is required for PO routes");
            }
            Supplier supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown supplier: " + supplierId));
            if (!supplier.isActive()) {
                throw new IllegalArgumentException("Supplier is not Active: " + supplierId);
            }
            if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("A positive unit_price is required for PO routes");
            }
        }

        Map<String, Object> before = snapshot(decision);
        if (decision.getOriginalDecisionJson() == null) {
            decision.setOriginalDecisionJson(write(before));
        }
        decision.setOverrideComment(comment);
        decision.setOverriddenBy(actor);
        decision.setOverriddenAt(LocalDateTime.now());

        if (targetIsPo) {
            decision.setSupplierId(supplierId);
            decision.setUnitPrice(unitPrice);
            decision.setDecision("PO");
        } else {
            decision.setSupplierId(null);
            decision.setUnitPrice(null);
            decision.setDecision("NOT_PROCESSED".equals(targetRoute) ? "NOT_PROCESSED" : "SOURCING_REQUEST");
            if (body.get("suggested_supplier_ids") != null) {
                decision.setSuggestedSupplierIds(joined(body.get("suggested_supplier_ids")));
            }
        }
        decision.setRoute(targetRoute);
        decision.setReason((decision.getReason() == null ? "" : decision.getReason() + " | ")
                + "Override by " + actor + ": " + comment);

        if (targetIsPo) {
            decision.setPoId(ensurePurchaseOrder(decision, supplierId, targetRoute));
            removeSourcingEvent(decision.getRequirementId());
        } else {
            detachFromPurchaseOrders(decision.getRequirementId());
            decision.setPoId(null);
            reconcileSourcingEvent(decision, targetRoute);
        }
        decisionRepository.save(decision);

        AuditLog audit = new AuditLog();
        audit.setActor(actor);
        audit.setAction("DECISION_OVERRIDE");
        audit.setTarget("decision:" + decisionId);
        audit.setBeforeJson(write(before));
        audit.setAfterJson(write(snapshot(decision)));
        audit.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(audit);
        return decision;
    }

    private String ensurePurchaseOrder(Decision decision, String supplierId, String route) {
        Requirement requirement = requirementRepository.findById(decision.getRequirementId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Requirement not found for decision: " + decision.getId()));
        PurchaseOrder existing = purchaseOrderForRequirement(requirement.getId());
        if (existing != null && supplierId.equals(existing.getSupplierId()) && route.equals(existing.getRoute())) {
            ensurePoLine(existing.getPoId(), requirement.getId());
            return existing.getPoId();
        }
        if (existing != null) {
            detachFromPurchaseOrders(requirement.getId());
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setPoId(uniqueOverridePoId(decision.getId()));
        po.setSupplierId(supplierId);
        po.setPlant(requirement.getPlant());
        po.setRoute(route);
        po.setCreatedFromRequirement(requirement.getId());
        po.setFirstRequirementAt(requirement.getFirstCreatedAt() == null
                ? LocalDateTime.now() : requirement.getFirstCreatedAt());
        po.setApprovalStatus("PROPOSED");
        purchaseOrderRepository.save(po);
        ensurePoLine(po.getPoId(), requirement.getId());
        return po.getPoId();
    }

    private PurchaseOrder purchaseOrderForRequirement(Long requirementId) {
        for (PoLine line : poLineRepository.findByRequirementId(requirementId)) {
            PurchaseOrder po = purchaseOrderRepository.findById(line.getPoId()).orElse(null);
            if (po != null) {
                return po;
            }
        }
        return null;
    }

    private void ensurePoLine(String poId, Long requirementId) {
        boolean exists = poLineRepository.findByPoId(poId).stream()
                .anyMatch(line -> requirementId.equals(line.getRequirementId()));
        if (!exists) {
            PoLine line = new PoLine();
            line.setPoId(poId);
            line.setRequirementId(requirementId);
            poLineRepository.save(line);
        }
    }

    private String uniqueOverridePoId(Long decisionId) {
        String base = "PO-OVR-" + decisionId;
        String candidate = base;
        int suffix = 1;
        while (purchaseOrderRepository.existsById(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private void detachFromPurchaseOrders(Long requirementId) {
        List<PoLine> lines = poLineRepository.findByRequirementId(requirementId);
        Set<String> affected = new java.util.LinkedHashSet<>();
        for (PoLine line : lines) {
            affected.add(line.getPoId());
        }
        poLineRepository.deleteAll(lines);
        for (String poId : affected) {
            if (poLineRepository.findByPoId(poId).isEmpty()) {
                purchaseOrderRepository.deleteById(poId);
            }
        }
    }

    private void reconcileSourcingEvent(Decision decision, String route) {
        List<SourcingEvent> events = sourcingEventRepository.findByRequirementId(decision.getRequirementId());
        if ("NOT_PROCESSED".equals(route)) {
            for (SourcingEvent event : events) {
                if ("SENT".equals(event.getApprovalStatus())) {
                    throw new IllegalArgumentException("A sent sourcing event cannot be overridden");
                }
            }
            sourcingEventRepository.deleteAll(events);
            return;
        }
        SourcingEvent event = events.isEmpty() ? null : events.get(0);
        if (event != null && "SENT".equals(event.getApprovalStatus())) {
            throw new IllegalArgumentException("A sent sourcing event cannot be overridden");
        }
        if (event == null) {
            event = new SourcingEvent();
            event.setRequirementId(decision.getRequirementId());
            event.setApprovalStatus("PENDING");
        } else if ("APPROVED".equals(event.getApprovalStatus())) {
            event.setApprovalStatus("PENDING");
            event.setApprovedBy(null);
            event.setApprovedAt(null);
        }
        event.setRoute(route);
        event.setSuggestedSupplierIds(decision.getSuggestedSupplierIds());
        sourcingEventRepository.save(event);
    }

    private void removeSourcingEvent(Long requirementId) {
        List<SourcingEvent> events = sourcingEventRepository.findByRequirementId(requirementId);
        for (SourcingEvent event : events) {
            if ("SENT".equals(event.getApprovalStatus())) {
                throw new IllegalArgumentException("A sent sourcing event cannot be overridden");
            }
        }
        sourcingEventRepository.deleteAll(events);
    }

    @Transactional
    public AuditLog record(String actor, String action, String target, Map<String, Object> after) {
        AuditLog audit = new AuditLog();
        audit.setActor(actor);
        audit.setAction(action);
        audit.setTarget(target);
        audit.setAfterJson(after == null ? null : write(after));
        audit.setCreatedAt(LocalDateTime.now());
        return auditLogRepository.save(audit);
    }

    private Map<String, Object> snapshot(Decision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision.getDecision());
        body.put("route", decision.getRoute());
        body.put("supplier_id", decision.getSupplierId());
        body.put("unit_price", decision.getUnitPrice());
        body.put("group_value_inr", decision.getGroupValueInr());
        body.put("suggested_supplier_ids", decision.getSuggestedSupplierIds());
        body.put("policy_version", decision.getPolicyVersion());
        body.put("clause", decision.getClause());
        body.put("reason", decision.getReason());
        body.put("po_id", decision.getPoId());
        body.put("approval_status", decision.getApprovalStatus());
        return body;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize audit entry", e);
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unit_price must be numeric", e);
        }
    }

    private String joined(Object value) {
        if (value instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder();
            for (Object item : iterable) {
                if (item == null || String.valueOf(item).isBlank()) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append(';');
                }
                result.append(item);
            }
            return result.toString();
        }
        return string(value);
    }
}
