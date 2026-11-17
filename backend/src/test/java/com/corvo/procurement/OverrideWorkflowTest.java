package com.corvo.procurement;

import com.corvo.procurement.audit.AuditService;
import com.corvo.procurement.domain.AuditLog;
import com.corvo.procurement.domain.AuditLogRepository;
import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PoLineRepository;
import com.corvo.procurement.domain.PurchaseOrder;
import com.corvo.procurement.domain.PurchaseOrderRepository;
import com.corvo.procurement.domain.SourcingEvent;
import com.corvo.procurement.domain.SourcingEventRepository;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.workflow.DecisionWorkflowService;
import com.corvo.procurement.workflow.RunWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OverrideWorkflowTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(900_000);

    @Autowired
    private BatchService batchService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private DecisionWorkflowService workflowService;

    @Autowired
    private DecisionRepository decisionRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PoLineRepository poLineRepository;

    @Autowired
    private SourcingEventRepository sourcingEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RunWorkspaceService workspaceService;

    private Long lastRunId;

    @Test
    void poToSourcingOverrideRemovesPoLineAndCreatesPendingSourcingEvent() {
        poLineRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        Decision decision = run("ITM-1038", "LTR", "760", "CHK1", "2026-11-05");
        assertThat(decision.getRoute()).isEqualTo("SPOT_PO");
        assertThat(decision.getPoId()).isNotBlank();
        workflowService.approve(decision.getId(), Map.of("actor", "test-buyer"));
        String createdPoId = poLineRepository.findByRequirementId(decision.getRequirementId())
                .get(0)
                .getPoId();
        assertThat(purchaseOrderRepository.findById(createdPoId)).isPresent();

        Decision overridden = auditService.override(decision.getId(), Map.of(
                "route", "RFQ",
                "comment", "Move to competitive sourcing",
                "actor", "test-buyer"
        ));

        assertThat(overridden.getRoute()).isEqualTo("RFQ");
        assertThat(overridden.getDecision()).isEqualTo("SOURCING_REQUEST");
        assertThat(overridden.getSupplierId()).isNull();
        assertThat(overridden.getUnitPrice()).isNull();
        assertThat(overridden.getPoId()).isNull();
        assertThat(overridden.getOriginalDecisionJson()).contains("SPOT_PO");
        assertThat(poLineRepository.findByRequirementId(overridden.getRequirementId())).isEmpty();
        assertThat(purchaseOrderRepository.findById(createdPoId)).isEmpty();

        List<SourcingEvent> events = sourcingEventRepository.findByRequirementId(overridden.getRequirementId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getRoute()).isEqualTo("RFQ");
        assertThat(events.get(0).getApprovalStatus()).isEqualTo("PENDING");

        AuditLog audit = auditLogRepository.findAll().stream()
                .filter(log -> "DECISION_OVERRIDE".equals(log.getAction()))
                .filter(log -> ("decision:" + decision.getId()).equals(log.getTarget()))
                .findFirst()
                .orElseThrow();
        assertThat(audit.getBeforeJson()).contains("SPOT_PO");
        assertThat(audit.getAfterJson()).contains("RFQ");
    }

    @Test
    void sourcingToPoOverrideCreatesPoAndApprovalSucceeds() {
        Decision decision = run("ITM-1006", "KG", "220", "PUN1", "2026-11-02");
        assertThat(decision.getRoute()).isEqualTo("RFQ");
        assertThat(decision.getSupplierId()).isNull();
        assertThat(decision.getUnitPrice()).isNull();

        Decision overridden = auditService.override(decision.getId(), Map.of(
                "route", "SPOT_PO",
                "supplier_id", "S003",
                "unit_price", "1365.00",
                "comment", "Buyer selected an Active supplier",
                "actor", "test-buyer"
        ));

        assertThat(overridden.getRoute()).isEqualTo("SPOT_PO");
        assertThat(overridden.getDecision()).isEqualTo("PO");
        assertThat(overridden.getSupplierId()).isEqualTo("S003");
        assertThat(overridden.getUnitPrice()).isEqualByComparingTo("1365.00");
        assertThat(overridden.getPoId()).isNotBlank();
        assertThat(sourcingEventRepository.findByRequirementId(overridden.getRequirementId())).isEmpty();

        PurchaseOrder po = purchaseOrderRepository.findById(overridden.getPoId()).orElseThrow();
        assertThat(po.getSupplierId()).isEqualTo("S003");
        assertThat(po.getRoute()).isEqualTo("SPOT_PO");
        assertThat(poLineRepository.findByPoId(po.getPoId()))
                .extracting(line -> line.getRequirementId())
                .contains(overridden.getRequirementId());

        workflowService.approve(overridden.getId(), Map.of("actor", "test-buyer"));
        PurchaseOrder approved = purchaseOrderRepository.findById(po.getPoId()).orElseThrow();
        assertThat(approved.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(decisionRepository.findById(overridden.getId()).orElseThrow().getApprovalStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void workspaceReflectsOverrideCreatedPo() {
        Decision decision = run("ITM-1006", "KG", "220", "PUN1", "2026-11-02");
        Decision overridden = auditService.override(decision.getId(), Map.of(
                "route", "SPOT_PO",
                "supplier_id", "S003",
                "unit_price", "1365.00",
                "comment", "Workspace reconciliation",
                "actor", "test-buyer"
        ));

        Map<String, Object> workspace = workspaceService.workspace(lastRunId);
        List<Map<String, Object>> pos = (List<Map<String, Object>>) workspace.get("pos");
        assertThat(pos).anySatisfy(po -> {
            assertThat(po.get("poId")).isEqualTo(overridden.getPoId());
            assertThat(po.get("supplierId")).isEqualTo("S003");
            List<Map<String, Object>> requirements = (List<Map<String, Object>>) po.get("requirements");
            assertThat(requirements).extracting(row -> row.get("id")).contains(decision.getRequirementId());
        });
        List<Map<String, Object>> sourcing = (List<Map<String, Object>>) workspace.get("sourcing");
        assertThat(sourcing).noneMatch(row -> decision.getRequirementId().equals(row.get("requirementId")));
    }

    @Test
    void invalidPoOverrideIsRejectedBeforeAnyMutation() {
        Decision decision = run("ITM-1006", "KG", "220", "PUN1", "2026-11-02");
        assertThat(decision.getRoute()).isEqualTo("RFQ");

        assertThatThrownBy(() -> auditService.override(decision.getId(), Map.of(
                "route", "SPOT_PO",
                "supplier_id", "S003",
                "comment", "Missing price",
                "actor", "test-buyer"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit_price");

        Decision unchanged = decisionRepository.findById(decision.getId()).orElseThrow();
        assertThat(unchanged.getRoute()).isEqualTo("RFQ");
        assertThat(unchanged.getSupplierId()).isNull();
        assertThat(unchanged.getPoId()).isNull();
        assertThat(unchanged.getOriginalDecisionJson()).isNull();
    }

    @Test
    void blockedSupplierOverrideIsRejected() {
        Decision decision = run("ITM-1006", "KG", "220", "PUN1", "2026-11-02");

        assertThatThrownBy(() -> auditService.override(decision.getId(), Map.of(
                "route", "SPOT_PO",
                "supplier_id", "S004",
                "unit_price", "1365.00",
                "comment", "Blocked supplier",
                "actor", "test-buyer"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");
    }

    private Decision run(String itemCode, String uom, String quantity, String plant, String date) {
        long id = SEQUENCE.incrementAndGet();
        LocalDate requirementDate = LocalDate.parse(date);
        PrLineInput line = new PrLineInput(
                id,
                "PR-OVR-" + id,
                1,
                requirementDate.atTime(9, 0),
                "test-buyer",
                "Maintenance",
                plant,
                itemCode,
                itemCode,
                new BigDecimal(quantity),
                uom,
                null,
                requirementDate.plusDays(10),
                "Normal"
        );
        BatchService.BatchResponse response = batchService.processInputs(List.of(line), "override-test", "test");
        lastRunId = response.runId();
        return decisionRepository.findByRunId(response.runId()).get(0);
    }
}
