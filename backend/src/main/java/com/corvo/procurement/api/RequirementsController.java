package com.corvo.procurement.api;

import com.corvo.procurement.domain.Requirement;
import com.corvo.procurement.domain.RequirementRepository;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.workflow.RunWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RequirementsController {
    private final RunWorkspaceService workspaceService;
    private final BatchService batchService;
    private final RequirementRepository requirementRepository;

    public RequirementsController(RunWorkspaceService workspaceService,
                                  BatchService batchService,
                                  RequirementRepository requirementRepository) {
        this.workspaceService = workspaceService;
        this.batchService = batchService;
        this.requirementRepository = requirementRepository;
    }

    @GetMapping({"/requirements", "/workspace/requirements"})
    public List<Map<String, Object>> requirements(
            @RequestParam(value = "run_id", required = false) Long runId) {
        Long target = runId == null ? latestRunId() : runId;
        if (target == null) {
            return List.of();
        }
        return requirementsFor(target);
    }

    @GetMapping("/requirements/{id}")
    public Map<String, Object> requirement(@PathVariable Long id) {
        Requirement requirement = requirementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Requirement not found: " + id));
        Long runId = requirement.getRunId();
        if (runId == null) {
            throw new IllegalArgumentException("Requirement has no run: " + id);
        }
        for (Map<String, Object> row : requirementsFor(runId)) {
            if (String.valueOf(id).equals(String.valueOf(row.get("id")))) {
                return row;
            }
        }
        throw new IllegalArgumentException("Requirement not found in run " + runId + ": " + id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requirementsFor(Long runId) {
        Map<String, Object> workspace = workspaceService.workspace(runId);
        Object rows = workspace.get("requirements");
        if (rows instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private Long latestRunId() {
        List<Map<String, Object>> runs = batchService.runs();
        if (runs.isEmpty()) {
            return null;
        }
        Object id = runs.get(0).get("id");
        return id == null ? null : Long.valueOf(String.valueOf(id));
    }
}
