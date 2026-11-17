package com.corvo.procurement;

import com.corvo.procurement.domain.PolicyAmendment;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.domain.PolicyVersionRepository;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.corvo.procurement.policy.ParameterResolver;
import com.corvo.procurement.policy.PolicyService;
import com.corvo.procurement.policy.amendment.PolicyAmendmentService;
import com.corvo.procurement.policy.amendment.PolicyValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PolicyAmendmentTest {
    private static final LocalDate BEFORE = LocalDate.parse("2026-11-05");
    private static final LocalDate AFTER = LocalDate.parse("2026-11-18");

    @Autowired
    private PolicyAmendmentService amendmentService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Test
    void setParameterChangesOnlyEligibleDatesAndResolvesAtRuntime() {
        long versionsBefore = versionRepository.count();
        PolicyAmendment amendment = create(LocalDate.parse("2026-11-10"),
                operation("set_parameter", "key", "spot_limit", "value", 100000));
        amendmentService.validate(amendment.getId());
        Map<String, Object> diff = amendmentService.preview(amendment.getId());
        assertThat(diff.get("changes")).asList().isNotEmpty();
        assertThat(diff.get("content_hash")).isNotNull();

        PolicyVersion version = amendmentService.approve(amendment.getId(), "procurement", false);
        assertThat(version.getStatus()).isEqualTo("ACTIVE");
        assertThat(versionRepository.count()).isEqualTo(versionsBefore + 1);

        PolicySnapshot before = policyService.selectSnapshot(BEFORE).orElseThrow();
        assertThat(before.label()).isEqualTo("v5.0");
        assertThat(new ParameterResolver(before.parameters()).resolveDecimal("spot_limit", facts("ITM-1038", "Hydraulics & Pneumatics", "CHK1")))
                .isEqualByComparingTo("200000");

        PolicySnapshot after = policyService.selectSnapshot(AFTER).orElseThrow();
        assertThat(after.id()).isEqualTo(version.getId());
        assertThat(new ParameterResolver(after.parameters()).resolveDecimal("spot_limit", facts("ITM-1038", "Hydraulics & Pneumatics", "CHK1")))
                .isEqualByComparingTo("100000");
    }

    @Test
    void scopedParameterAppliesOnlyToMatchingCategory() {
        Map<String, Object> scope = Map.of("category", "Safety & PPE");
        PolicyAmendment amendment = create(AFTER,
                operation("set_parameter", "key", "spot_limit", "value", 100000, "scope_json", scope));
        amendmentService.preview(amendment.getId());
        amendmentService.approve(amendment.getId(), "procurement", false);

        PolicySnapshot snapshot = policyService.selectSnapshot(AFTER).orElseThrow();
        ParameterResolver resolver = new ParameterResolver(snapshot.parameters());
        assertThat(resolver.resolveDecimal("spot_limit", facts("ITM-1050", "Safety & PPE", "PUN1")))
                .isEqualByComparingTo("100000");
        assertThat(resolver.resolveDecimal("spot_limit", facts("ITM-1038", "Hydraulics & Pneumatics", "CHK1")))
                .isEqualByComparingTo("200000");
    }

    @Test
    void addingScopedRuleStoresLeadingScopeLeaf() {
        PolicyAmendment amendment = create(AFTER,
                operation("add_rule",
                        "rule_key", "v5.0.5.2b",
                        "priority", 35,
                        "clause_ref", "5.2b",
                        "name", "CHK1 high-value emergency",
                        "scope_json", Map.of("plant", "CHK1"),
                        "condition_json", Map.of("all", List.of(
                                Map.of("fact", "urgency", "op", "eq", "value", "Emergency"),
                                Map.of("fact", "value_inr", "op", "gte", "value", 400000))),
                        "outcome_json", Map.of("route", "RFP", "decision", "SOURCING_REQUEST",
                                "supplier_strategy", "preferred_top_n", "suggestion_count", 5),
                        "enabled", true));
        amendmentService.preview(amendment.getId());
        amendmentService.approve(amendment.getId(), "procurement", false);

        PolicySnapshot snapshot = policyService.selectSnapshot(AFTER).orElseThrow();
        var rule = snapshot.rules().stream().filter(r -> "v5.0.5.2b".equals(r.ruleKey())).findFirst().orElseThrow();
        assertThat(rule.conditionJson()).contains("\"plant\"").contains("CHK1");
        assertThat(rule.scopeJson()).contains("CHK1");
    }

    @Test
    void invalidDraftsAreRejectedWithoutCreatingVersions() {
        long versionsBefore = versionRepository.count();

        assertInvalid(operation("add_rule",
                "rule_key", "bad.fact",
                "priority", 15,
                "condition_json", Map.of("all", List.of(Map.of("fact", "not_a_fact", "op", "eq", "value", "x"))),
                "outcome_json", Map.of("route", "RFQ", "decision", "SOURCING_REQUEST",
                        "supplier_strategy", "preferred_top_n", "suggestion_count", 3),
                "enabled", true));

        assertInvalid(operation("add_rule",
                "rule_key", "bad.param",
                "priority", 15,
                "condition_json", Map.of("all", List.of(Map.of("fact", "value_inr", "op", "lt", "param", "missing_param"))),
                "outcome_json", Map.of("route", "RFQ", "decision", "SOURCING_REQUEST",
                        "supplier_strategy", "preferred_top_n", "suggestion_count", 3),
                "enabled", true));

        assertInvalid(operation("add_rule",
                "rule_key", "bad.both",
                "priority", 15,
                "condition_json", Map.of("all", List.of(Map.of("fact", "value_inr", "op", "lt",
                        "value", 100, "param", "spot_limit"))),
                "outcome_json", Map.of("route", "RFQ", "decision", "SOURCING_REQUEST",
                        "supplier_strategy", "preferred_top_n", "suggestion_count", 3),
                "enabled", true));

        assertInvalid(operation("add_rule",
                "rule_key", "bad.route",
                "priority", 15,
                "condition_json", Map.of("all", List.of(Map.of("fact", "value_inr", "op", "lt", "value", 100))),
                "outcome_json", Map.of("route", "BOGUS", "decision", "SOURCING_REQUEST"),
                "enabled", true));

        assertInvalid(operation("add_rule",
                "rule_key", "bad.priority",
                "priority", 50,
                "condition_json", Map.of("all", List.of(Map.of("fact", "value_inr", "op", "lt", "value", 100))),
                "outcome_json", Map.of("route", "RFQ", "decision", "SOURCING_REQUEST",
                        "supplier_strategy", "preferred_top_n", "suggestion_count", 3),
                "enabled", true));

        assertInvalid(operation("add_rule",
                "rule_key", "bad.supplier",
                "priority", 15,
                "condition_json", Map.of("all", List.of(Map.of("fact", "value_inr", "op", "lt", "value", 100))),
                "outcome_json", Map.of("route", "SPOT_PO", "decision", "PO",
                        "supplier_strategy", "first_preferred_supplier", "supplier_id", "S004"),
                "enabled", true));

        assertThat(versionRepository.count()).isEqualTo(versionsBefore);
    }

    @Test
    void missingEffectiveDateIsRejected() {
        assertThatThrownBy(() -> amendmentService.create("manual", "text", null, null,
                List.of(operation("set_parameter", "key", "spot_limit", "value", 100000)), "tester"))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Effective date");
    }

    @Test
    void stalePreviewIsRejectedAtApproval() {
        PolicyAmendment amendment = create(LocalDate.parse("2026-11-10"),
                operation("set_parameter", "key", "spot_limit", "value", 100000));
        amendmentService.preview(amendment.getId());
        amendmentService.addOperation(amendment.getId(),
                operation("set_parameter", "key", "spot_limit", "value", 150000), "tester");

        assertThatThrownBy(() -> amendmentService.approve(amendment.getId(), "procurement", false))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining("Stale draft");
    }

    @Test
    void twoStepApprovalRequiresActivationBeforeSelection() {
        PolicyAmendment amendment = create(LocalDate.parse("2026-11-10"),
                operation("set_parameter", "key", "spot_limit", "value", 100000));
        amendmentService.preview(amendment.getId());
        PolicyVersion approved = amendmentService.approve(amendment.getId(), "procurement", true);
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getActivatedAt()).isNull();
        assertThat(policyService.selectSnapshot(AFTER).orElseThrow().id()).isNotEqualTo(approved.getId());

        PolicyVersion activated = amendmentService.activate(amendment.getId(), "procurement");
        assertThat(activated.getStatus()).isEqualTo("ACTIVE");
        assertThat(policyService.selectSnapshot(AFTER).orElseThrow().id()).isEqualTo(activated.getId());
    }

    @Test
    void sameEffectiveDateTieBreaksByApprovalThenIdAndNoVersionMeansNotProcessed() {
        PolicyAmendment first = create(LocalDate.parse("2026-12-01"),
                operation("set_parameter", "key", "emergency_limit", "value", 300000));
        amendmentService.preview(first.getId());
        PolicyVersion firstVersion = amendmentService.approve(first.getId(), "procurement", false);

        PolicyAmendment second = create(LocalDate.parse("2026-12-01"),
                operation("set_parameter", "key", "emergency_limit", "value", 400000));
        amendmentService.preview(second.getId());
        PolicyVersion secondVersion = amendmentService.approve(second.getId(), "procurement", false);

        assertThat(secondVersion.getId()).isGreaterThan(firstVersion.getId());
        assertThat(policyService.selectSnapshot(LocalDate.parse("2026-12-05")).orElseThrow().id())
                .isEqualTo(secondVersion.getId());
        assertThat(policyService.selectSnapshot(LocalDate.parse("2026-01-01"))).isEmpty();
    }

    private void assertInvalid(Map<String, Object> operation) {
        PolicyAmendment amendment = create(AFTER, operation);
        assertThatThrownBy(() -> amendmentService.validate(amendment.getId()))
                .isInstanceOf(PolicyValidationException.class);
        assertThat(amendmentService.get(amendment.getId()).getStatus()).isEqualTo("DRAFT");
    }

    private PolicyAmendment create(LocalDate effectiveFrom, Map<String, Object> operation) {
        return amendmentService.create("manual", "test amendment", null, effectiveFrom,
                List.of(operation), "tester");
    }

    private Map<String, Object> operation(String type, Object... pairs) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", type);
        for (int i = 0; i < pairs.length; i += 2) {
            operation.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return operation;
    }

    private RequirementFacts facts(String itemCode, String category, String plant) {
        return new RequirementFacts(itemCode, "Material", category, plant,
                new BigDecimal("100000"), "Normal", BigDecimal.ONE, "NOS", 5,
                false, false, true, false);
    }
}
