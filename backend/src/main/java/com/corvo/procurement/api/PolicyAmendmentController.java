package com.corvo.procurement.api;

import com.corvo.procurement.domain.PolicyAmendment;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.policy.amendment.PolicyAmendmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policy-amendments")
public class PolicyAmendmentController {
    private final PolicyAmendmentService service;

    public PolicyAmendmentController(PolicyAmendmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<PolicyAmendment> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        LocalDate effectiveFrom = parseDate(firstNonNull(body.get("effective_from"), body.get("effectiveFrom")));
        List<Map<String, Object>> operations = body.get("operations") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        PolicyAmendment amendment = service.create(
                string(body.get("source")),
                string(body.get("raw_text")),
                string(firstNonNull(body.get("document_ref"), body.get("documentRef"))),
                effectiveFrom,
                operations,
                string(firstNonNull(body.get("created_by"), body.get("createdBy"))),
                true
        );
        return present(amendment);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return present(service.get(id));
    }

    @PostMapping("/{id}/validate")
    public Map<String, Object> validate(@PathVariable Long id) {
        return service.validate(id);
    }

    @PostMapping("/{id}/preview")
    public Map<String, Object> preview(@PathVariable Long id) {
        return service.preview(id);
    }

    @GetMapping("/{id}/diff")
    public Map<String, Object> diff(@PathVariable Long id) {
        return service.diff(id);
    }

    @GetMapping("/{id}/draft")
    public Map<String, Object> draft(@PathVariable Long id) {
        var draft = service.draft(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effective_from", draft.effectiveFrom());
        body.put("rules", draft.rules());
        body.put("parameters", draft.parameters());
        return body;
    }

    @PostMapping("/{id}/rules")
    public Map<String, Object> addRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> operation = new LinkedHashMap<>(body);
        operation.put("type", "add_rule");
        return present(service.addOperation(id, operation, string(body.get("actor"))));
    }

    @PatchMapping("/{id}/rules/{ruleKey}")
    public Map<String, Object> updateRule(@PathVariable Long id, @PathVariable String ruleKey,
                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "update_rule");
        operation.put("rule_key", ruleKey);
        operation.put("patch", body);
        return present(service.addOperation(id, operation, string(body.get("actor"))));
    }

    @DeleteMapping("/{id}/rules/{ruleKey}")
    public Map<String, Object> removeRule(@PathVariable Long id, @PathVariable String ruleKey) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "remove_rule");
        operation.put("rule_key", ruleKey);
        return present(service.addOperation(id, operation, null));
    }

    @PostMapping("/{id}/rules/{ruleKey}/reorder")
    public Map<String, Object> reorderRule(@PathVariable Long id, @PathVariable String ruleKey,
                                           @RequestBody Map<String, Object> body) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "reorder_rule");
        operation.put("rule_key", ruleKey);
        operation.put("priority", body.get("priority"));
        return present(service.addOperation(id, operation, string(body.get("actor"))));
    }

    @PostMapping("/{id}/parameters")
    public Map<String, Object> setParameter(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> operation = new LinkedHashMap<>(body);
        operation.put("type", "set_parameter");
        return present(service.addOperation(id, operation, string(body.get("actor"))));
    }

    @PatchMapping("/{id}/parameters/{key}")
    public Map<String, Object> updateParameter(@PathVariable Long id, @PathVariable String key,
                                               @RequestBody Map<String, Object> body) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "set_parameter");
        operation.put("key", key);
        operation.put("value", body.get("value"));
        if (body.containsKey("scope_json")) {
            operation.put("scope_json", body.get("scope_json"));
        }
        if (body.containsKey("value_type")) {
            operation.put("value_type", body.get("value_type"));
        }
        return present(service.addOperation(id, operation, string(body.get("actor"))));
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        boolean twoStep = Boolean.parseBoolean(String.valueOf(
                firstNonNull(payload.get("two_step"), payload.getOrDefault("twoStep", false))));
        String actor = string(firstNonNull(payload.get("actor"), "procurement"));
        PolicyVersion version = service.approve(id, actor, twoStep);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", version);
        response.put("amendment", service.get(id));
        return response;
    }

    @PostMapping("/{id}/reject")
    public PolicyAmendment reject(@PathVariable Long id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return service.reject(id, string(firstNonNull(payload.get("actor"), "procurement")));
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        PolicyVersion version = service.activate(id, string(firstNonNull(payload.get("actor"), "procurement")));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", version);
        response.put("amendment", service.get(id));
        return response;
    }

    private Map<String, Object> present(PolicyAmendment amendment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", amendment.getId());
        body.put("source", amendment.getSource());
        body.put("raw_text", amendment.getRawText());
        body.put("document_ref", amendment.getDocumentRef());
        body.put("status", amendment.getStatus());
        body.put("effective_from", amendment.getEffectiveFrom());
        body.put("created_by", amendment.getCreatedBy());
        body.put("approved_by", amendment.getApprovedBy());
        body.put("approved_at", amendment.getApprovedAt());
        body.put("applied_version_id", amendment.getAppliedVersionId());
        body.put("applied_at", amendment.getAppliedAt());
        body.put("operations", service.operations(amendment.getId()));
        return body;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDate parseDate(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
