package com.corvo.procurement.api;

import com.corvo.procurement.ai.AiClient;
import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.policy.PolicyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PolicyController {
    private final PolicyService policyService;
    private final AiClient aiClient;

    public PolicyController(PolicyService policyService, AiClient aiClient) {
        this.policyService = policyService;
        this.aiClient = aiClient;
    }

    @GetMapping("/policies")
    public List<PolicyVersion> versions() {
        return policyService.versions();
    }

    @GetMapping("/policies/{versionId}")
    public Map<String, Object> version(@PathVariable Long versionId) {
        PolicyVersion version = policyService.version(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Policy version not found: " + versionId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("rules", policyService.rules(versionId));
        body.put("parameters", policyService.parameters(versionId));
        body.put("facts", policyService.facts());
        return body;
    }

    @GetMapping("/policy-facts")
    public List<PolicyFact> facts() {
        return policyService.facts();
    }

    @PostMapping("/policies/describe")
    public Map<String, Object> describe(@RequestBody Map<String, Object> body) {
        return aiClient.post("/policy/describe", body);
    }

    @PostMapping("/policies/paste")
    public Map<String, Object> paste(@RequestBody Map<String, Object> body) {
        return aiClient.post("/policy/extract", body);
    }

    @PostMapping(value = "/policies/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam Map<String, String> fields) {
        try {
            Map<String, String> forwarded = new LinkedHashMap<>(fields);
            forwarded.remove("file");
            return aiClient.upload("/policy/upload", file.getBytes(), file.getOriginalFilename(), forwarded);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read uploaded policy file", e);
        }
    }
}
