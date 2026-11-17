package com.corvo.procurement.api;

import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.ingestion.CsvExporter;
import com.corvo.procurement.workflow.RunWorkspaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public class BatchController {
    private final BatchService batchService;
    private final CsvExporter csvExporter;
    private final RunWorkspaceService workspaceService;

    public BatchController(BatchService batchService, CsvExporter csvExporter, RunWorkspaceService workspaceService) {
        this.batchService = batchService;
        this.csvExporter = csvExporter;
        this.workspaceService = workspaceService;
    }

    @PostMapping(value = "/process-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchService.BatchResponse processBatch(@RequestParam(value = "file", required = false) MultipartFile file,
                                                   @RequestParam(value = "uploadedBy", defaultValue = "buyer") String uploadedBy) {
        try {
            if (file == null || file.isEmpty()) {
                return batchService.processSample(uploadedBy);
            }
            return batchService.process(file.getBytes(), file.getOriginalFilename(), uploadedBy);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read uploaded CSV", e);
        }
    }

    @PostMapping("/process-batch/sample")
    public BatchService.BatchResponse processSample(@RequestParam(value = "uploadedBy", defaultValue = "buyer") String uploadedBy) {
        return batchService.processSample(uploadedBy);
    }

    @GetMapping("/runs")
    public java.util.List<java.util.Map<String, Object>> runs() {
        return batchService.runs();
    }

    @GetMapping("/runs/{id}")
    public EngineResult run(@PathVariable Long id) {
        return batchService.result(id);
    }

    @GetMapping("/runs/{id}/workspace")
    public java.util.Map<String, Object> workspace(@PathVariable Long id) {
        return workspaceService.workspace(id);
    }

    @GetMapping("/runs/{id}/export.csv")
    public ResponseEntity<String> export(@PathVariable Long id) {
        EngineResult result = batchService.result(id);
        String csv = csvExporter.export(result.lines());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=run-" + id + "-decisions.csv")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }
}
