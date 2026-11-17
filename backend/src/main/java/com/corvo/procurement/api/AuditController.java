package com.corvo.procurement.api;

import com.corvo.procurement.audit.AuditService;
import com.corvo.procurement.domain.AuditLog;
import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.workflow.DecisionWorkflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuditController {
    private final AuditService auditService;
    private final DecisionWorkflowService workflowService;

    public AuditController(AuditService auditService, DecisionWorkflowService workflowService) {
        this.auditService = auditService;
        this.workflowService = workflowService;
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() {
        return auditService.list();
    }

    @GetMapping("/decisions")
    public List<Decision> decisions(@org.springframework.web.bind.annotation.RequestParam("run_id") Long runId) {
        return auditService.decisionsByRun(runId);
    }

    @PostMapping("/decisions/{id}/override")
    public Decision override(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return auditService.override(id, body);
    }

    @PostMapping("/decisions/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return workflowService.approve(id, body == null ? Map.of() : body);
    }

    @PostMapping("/sourcing-events/{id}/send")
    public Map<String, Object> send(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return workflowService.sendSourcing(id, body == null ? Map.of() : body);
    }
}
