package com.corvo.procurement.api;

import com.corvo.procurement.ai.AiClient;
import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.policy.PolicyJson;
import com.corvo.procurement.policy.PolicyService;
import com.corvo.procurement.policy.ScopeJson;
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

    @GetMapping("/policies/{versionId}/parameters")
    public List<Map<String, Object>> parameters(@PathVariable Long versionId,
                                                @RequestParam(value = "key", required = false) String key) {
        policyService.version(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Policy version not found: " + versionId));
        return policyService.parameters(versionId).stream()
                .filter(parameter -> key == null || key.isBlank() || key.equals(parameter.getParamKey()))
                .map(this::presentParameter)
                .toList();
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

    private Map<String, Object> presentParameter(PolicyParameter parameter) {
        Map<String, String> scope = ScopeJson.parse(parameter.getScopeJson());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", parameter.getId());
        body.put("key", parameter.getParamKey());
        body.put("param_key", parameter.getParamKey());
        body.put("value", PolicyJson.MAPPER.convertValue(PolicyJson.parse(parameter.getValueJson()), Object.class));
        body.put("value_json", parameter.getValueJson());
        body.put("value_type", parameter.getValueType());
        body.put("scope", scope);
        body.put("scope_json", parameter.getScopeJson() == null
                ? null : PolicyJson.MAPPER.convertValue(PolicyJson.parse(parameter.getScopeJson()), Object.class));
        if (scope.containsKey("category")) {
            body.put("category", scope.get("category"));
        }
        if (scope.containsKey("item_code")) {
            body.put("item_code", scope.get("item_code"));
        }
        if (scope.containsKey("plant")) {
            body.put("plant", scope.get("plant"));
        }
        return body;
    }
}
