package com.corvo.procurement.workflow;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DecisionWorkflowService {
    private final DecisionRepository decisionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoLineRepository poLineRepository;
    private final RequirementRepository requirementRepository;
    private final SourcingEventRepository sourcingEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public DecisionWorkflowService(DecisionRepository decisionRepository,
                                   PurchaseOrderRepository purchaseOrderRepository,
                                   PoLineRepository poLineRepository,
                                   RequirementRepository requirementRepository,
                                   SourcingEventRepository sourcingEventRepository,
                                   AuditLogRepository auditLogRepository,
                                   ObjectMapper objectMapper) {
        this.decisionRepository = decisionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poLineRepository = poLineRepository;
        this.requirementRepository = requirementRepository;
        this.sourcingEventRepository = sourcingEventRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> approve(Long decisionId, Map<String, Object> body) {
        Decision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));
        if ("NOT_PROCESSED".equals(decision.getRoute())) {
            throw new IllegalArgumentException("A NOT_PROCESSED line cannot be approved");
        }
        if ("APPROVED".equals(decision.getApprovalStatus())) {
            return response(decision);
        }
        String actor = string(firstNonNull(body.get("actor"), "buyer"));
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> before = snapshot(decision);

        decision.setApprovalStatus("APPROVED");
        decision.setApprovedBy(actor);
        decision.setApprovedAt(now);
        decisionRepository.saveAndFlush(decision);

        if ("PO".equals(decision.getDecision())) {
            PurchaseOrder po = poFor(decision);
            if (po == null) {
                po = createPurchaseOrder(decision);
            }
            if (po == null) {
                throw new IllegalArgumentException("No purchase order is linked to decision " + decisionId);
            }
            po.setApprovalStatus(allPoRequirementsApproved(decision) ? "APPROVED" : "PARTIALLY_APPROVED");
            po.setApprovedBy(actor);
            po.setApprovedAt(now);
            purchaseOrderRepository.save(po);
        } else {
            SourcingEvent event = sourcingEventFor(decision);
            if (event == null) {
                event = new SourcingEvent();
                event.setRoute(decision.getRoute());
                event.setRequirementId(decision.getRequirementId());
                event.setSuggestedSupplierIds(decision.getSuggestedSupplierIds());
                event.setApprovalStatus("PENDING");
            }
            event.setApprovalStatus("APPROVED");
            event.setApprovedBy(actor);
            event.setApprovedAt(now);
            sourcingEventRepository.save(event);
        }

        Map<String, Object> after = snapshot(decision);
        audit(actor, "DECISION_APPROVE", "decision:" + decisionId, before, after);
        return response(decision);
    }

    @Transactional
    public Map<String, Object> sendSourcing(Long eventId, Map<String, Object> body) {
        SourcingEvent event = sourcingEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Sourcing event not found: " + eventId));
        if (!"APPROVED".equals(event.getApprovalStatus()) && !"SENT".equals(event.getApprovalStatus())) {
            throw new IllegalArgumentException("Approve the sourcing request before sending it");
        }
        String actor = string(firstNonNull(body.get("actor"), "buyer"));
        if (!"SENT".equals(event.getApprovalStatus())) {
            event.setApprovalStatus("SENT");
            event.setSentBy(actor);
            event.setSentAt(LocalDateTime.now());
            sourcingEventRepository.save(event);
            audit(actor, "SOURCING_SENT", "sourcing-event:" + eventId, Map.of(), Map.of(
                    "route", event.getRoute(),
                    "requirement_id", event.getRequirementId(),
                    "status", event.getApprovalStatus()
            ));
        }
        return response(event);
    }

    @Transactional(readOnly = true)
    public List<SourcingEvent> sourcingEvents(List<Long> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return List.of();
        }
        return sourcingEventRepository.findByRequirementIdIn(requirementIds);
    }

    private PurchaseOrder poFor(Decision decision) {
        for (PoLine line : poLineRepository.findByRequirementId(decision.getRequirementId())) {
            PurchaseOrder po = purchaseOrderRepository.findById(line.getPoId()).orElse(null);
            if (po != null) {
                return po;
            }
        }
        return null;
    }

    private PurchaseOrder createPurchaseOrder(Decision decision) {
        if (decision.getPoId() == null || decision.getPoId().isBlank()) {
            return null;
        }
        List<Decision> group = decisionRepository.findByRunIdAndPoId(decision.getRunId(), decision.getPoId());
        if (group.isEmpty()) {
            group = List.of(decision);
        }
        Requirement anchor = earliestRequirement(group);
        if (anchor == null) {
            return null;
        }
        PurchaseOrder po = new PurchaseOrder();
        po.setPoId(uniquePoId(decision.getPoId(), decision.getRunId()));
        po.setSupplierId(decision.getSupplierId());
        po.setPlant(anchor.getPlant());
        po.setRoute(decision.getRoute());
        po.setCreatedFromRequirement(anchor.getId());
        po.setFirstRequirementAt(anchor.getFirstCreatedAt() == null
                ? LocalDateTime.now() : anchor.getFirstCreatedAt());
        po.setApprovalStatus("PROPOSED");
        purchaseOrderRepository.saveAndFlush(po);
        for (Decision member : group) {
            ensurePoLine(po.getPoId(), member.getRequirementId());
        }
        return po;
    }

    private Requirement earliestRequirement(List<Decision> group) {
        Requirement anchor = null;
        for (Decision member : group) {
            Requirement candidate = requirementRepository.findById(member.getRequirementId()).orElse(null);
            if (candidate == null) {
                continue;
            }
            if (anchor == null || earlier(candidate, anchor)) {
                anchor = candidate;
            }
        }
        return anchor;
    }

    private boolean earlier(Requirement candidate, Requirement anchor) {
        if (candidate.getFirstCreatedAt() == null) {
            return false;
        }
        if (anchor.getFirstCreatedAt() == null) {
            return true;
        }
        int comparison = candidate.getFirstCreatedAt().compareTo(anchor.getFirstCreatedAt());
        if (comparison != 0) {
            return comparison < 0;
        }
        return candidate.getId() != null && (anchor.getId() == null || candidate.getId() < anchor.getId());
    }

    private String uniquePoId(String candidate, Long runId) {
        if (!purchaseOrderRepository.existsById(candidate)) {
            return candidate;
        }
        String base = candidate + "-R" + runId;
        String value = base;
        int suffix = 1;
        while (purchaseOrderRepository.existsById(value)) {
            value = base + "-" + suffix++;
        }
        return value;
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

    private boolean allPoRequirementsApproved(Decision decision) {
        List<Decision> decisions = decisionRepository.findByRunIdAndPoId(
                decision.getRunId(), decision.getPoId());
        if (decisions.isEmpty()) {
            return false;
        }
        return decisions.stream().allMatch(d -> "APPROVED".equals(d.getApprovalStatus()));
    }

    private SourcingEvent sourcingEventFor(Decision decision) {
        List<SourcingEvent> events = sourcingEventRepository.findByRequirementId(decision.getRequirementId());
        return events.isEmpty() ? null : events.get(0);
    }

    private Map<String, Object> response(Decision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("approval_status", decision.getApprovalStatus());
        body.put("approved_by", decision.getApprovedBy());
        body.put("approved_at", decision.getApprovedAt());
        return body;
    }

    private Map<String, Object> response(SourcingEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourcing_event", event);
        body.put("status", event.getApprovalStatus());
        body.put("sent_by", event.getSentBy());
        body.put("sent_at", event.getSentAt());
        return body;
    }

    private Map<String, Object> snapshot(Decision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision.getDecision());
        body.put("route", decision.getRoute());
        body.put("supplier_id", decision.getSupplierId());
        body.put("group_value_inr", decision.getGroupValueInr());
        body.put("approval_status", decision.getApprovalStatus());
        body.put("approved_by", decision.getApprovedBy());
        body.put("approved_at", decision.getApprovedAt());
        body.put("po_id", decision.getPoId());
        return body;
    }

    private void audit(String actor, String action, String target,
                       Map<String, Object> before, Map<String, Object> after) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action);
        log.setTarget(target);
        log.setBeforeJson(write(before));
        log.setAfterJson(write(after));
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(log);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize workflow audit entry", e);
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
