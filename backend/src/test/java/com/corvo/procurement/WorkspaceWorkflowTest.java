package com.corvo.procurement;

import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PoLine;
import com.corvo.procurement.domain.PoLineRepository;
import com.corvo.procurement.domain.PurchaseOrder;
import com.corvo.procurement.domain.PurchaseOrderRepository;
import com.corvo.procurement.domain.SourcingEvent;
import com.corvo.procurement.domain.SourcingEventRepository;
import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.PoGroup;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.workflow.DecisionWorkflowService;
import com.corvo.procurement.workflow.RunWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class WorkspaceWorkflowTest {
    @Autowired
    private BatchService batchService;

    @Autowired
    private DecisionWorkflowService workflowService;

    @Autowired
    private RunWorkspaceService workspaceService;

    @Autowired
    private DecisionRepository decisionRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PoLineRepository poLineRepository;

    @Autowired
    private SourcingEventRepository sourcingEventRepository;

    @Test
    void approvingDecisionsPromotesPoAndSourcingRequestsCanBeSent() {
        BatchService.BatchResponse response = batchService.processSample("workflow-test");
        EngineResult result = response.result();

        PoGroup group = result.poGroups().stream()
                .filter(po -> po.requirementIds().size() > 1)
                .findFirst()
                .orElseThrow();
        List<Decision> poDecisions = decisionRepository.findByRunIdAndPoId(response.runId(), group.poId());
        assertThat(poDecisions).hasSize(group.requirementIds().size());
        assertThat(poLineRepository.findByRequirementId(poDecisions.get(0).getRequirementId())).isEmpty();

        workflowService.approve(poDecisions.get(0).getId(), Map.of("actor", "test-buyer"));
        PurchaseOrder partial = poFor(poDecisions.get(0).getRequirementId());
        assertThat(partial.getApprovalStatus()).isEqualTo("PARTIALLY_APPROVED");

        for (Decision decision : poDecisions.subList(1, poDecisions.size())) {
            workflowService.approve(decision.getId(), Map.of("actor", "test-buyer"));
        }
        PurchaseOrder approved = poFor(poDecisions.get(0).getRequirementId());
        assertThat(approved.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(approved.getApprovedBy()).isEqualTo("test-buyer");

        Decision sourcingDecision = decisionRepository.findByRunId(response.runId()).stream()
                .filter(decision -> "RFQ".equals(decision.getRoute()))
                .findFirst()
                .orElseThrow();
        workflowService.approve(sourcingDecision.getId(), Map.of("actor", "test-buyer"));
        SourcingEvent event = sourcingEventRepository.findByRequirementId(sourcingDecision.getRequirementId()).get(0);
        assertThat(event.getApprovalStatus()).isEqualTo("APPROVED");

        workflowService.sendSourcing(event.getId(), Map.of("actor", "test-buyer"));
        SourcingEvent sent = sourcingEventRepository.findById(event.getId()).orElseThrow();
        assertThat(sent.getApprovalStatus()).isEqualTo("SENT");
        assertThat(sent.getSentAt()).isNotNull();
    }

    @Test
    void workspaceReturnsRequirementsPosSourcingAndRowLevelIssues() {
        BatchService.BatchResponse response = batchService.processSample("workspace-test");
        Map<String, Object> workspace = workspaceService.workspace(response.runId());

        assertThat((List<?>) workspace.get("requirements")).hasSize(149);
        assertThat((List<?>) workspace.get("pos")).isEmpty();
        assertThat((List<?>) workspace.get("issues")).hasSize(2);

        List<?> sourcing = (List<?>) workspace.get("sourcing");
        assertThat(sourcing).isNotEmpty();

        Map<?, ?> first = (Map<?, ?>) ((List<?>) workspace.get("requirements")).get(0);
        assertThat(first.get("status")).isEqualTo("PROPOSED");
        assertThat((List<?>) first.get("prLines")).isNotEmpty();
        assertThat(first.get("policyVersion")).isEqualTo("v5.0");
    }

    private PurchaseOrder poFor(Long requirementId) {
        PoLine line = poLineRepository.findByRequirementId(requirementId).get(0);
        return purchaseOrderRepository.findById(line.getPoId()).orElseThrow();
    }
}
