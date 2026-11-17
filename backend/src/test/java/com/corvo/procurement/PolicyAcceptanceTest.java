package com.corvo.procurement;

import com.corvo.procurement.domain.ContractLineRepository;
import com.corvo.procurement.domain.ContractRepository;
import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PolicyAmendment;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.domain.PolicyVersionRepository;
import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.policy.amendment.PolicyAmendmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PolicyAcceptanceTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(950_000);
    private static final String ITEM = "ITM-1038";
    private static final String UOM = "LTR";
    private static final String PLANT = "CHK1";

    @Autowired
    private PolicyAmendmentService amendmentService;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private BatchService batchService;

    @Autowired
    private DecisionRepository decisionRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ContractLineRepository contractLineRepository;

    @Test
    void p1setParameterOnlyChangesEligibleDatesAndReprocessUsesNewVersion() {
        PolicyAmendment amendment = amendment(LocalDate.parse("2026-11-10"),
                operation("set_parameter", "key", "spot_limit", "value", 100000));
        amendmentService.preview(amendment.getId());
        PolicyVersion version = amendmentService.approve(amendment.getId(), "procurement", false);

        Decision before = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-05", "Normal");
        assertThat(before.getRoute()).isEqualTo("SPOT_PO");
        assertThat(before.getPolicyVersion()).isEqualTo("v5.0");
        assertThat(money(before.getGroupValueInr())).isEqualTo("126471.60");

        Decision after = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-18", "Normal");
        assertThat(after.getRoute()).isEqualTo("RFQ");
        assertThat(after.getPolicyVersion()).isEqualTo(version.getLabel());
    }

    @Test
    void p4ReorderingSpotBeforeEmergencyChangesMatchedRule() {
        Decision baseline = run(ITEM, new BigDecimal("901"), PLANT, "2026-11-18", "Emergency");
        assertThat(baseline.getRoute()).isEqualTo("EMERGENCY_PO");
        assertThat(baseline.getClause()).isEqualTo("5.3");

        PolicyAmendment amendment = amendment(LocalDate.parse("2026-11-18"),
                operation("reorder_rule", "rule_key", "v5.0.5.4", "priority", 35),
                operation("reorder_rule", "rule_key", "v5.0.5.3", "priority", 50));
        amendmentService.preview(amendment.getId());
        amendmentService.approve(amendment.getId(), "procurement", false);

        Decision reordered = run(ITEM, new BigDecimal("901"), PLANT, "2026-11-18", "Emergency");
        assertThat(reordered.getRoute()).isEqualTo("SPOT_PO");
        assertThat(reordered.getClause()).isEqualTo("5.4");
    }

    @Test
    void p6ManualAndExtractionSourcesProduceSameDiffAndContentHash() {
        LocalDate effective = LocalDate.parse("2026-12-01");
        Map<String, Object> operation = operation("set_parameter", "key", "spot_limit", "value", 100000);

        PolicyAmendment manual = amendmentService.create("manual", "manual editor", null, effective,
                List.of(operation), "tester");
        PolicyAmendment extraction = amendmentService.create("chat", "extracted text", null, effective,
                List.of(operation), "tester");
        Map<String, Object> manualDiff = amendmentService.preview(manual.getId());
        Map<String, Object> extractionDiff = amendmentService.preview(extraction.getId());
        assertThat(manualDiff.get("content_hash")).isEqualTo(extractionDiff.get("content_hash"));

        PolicyVersion manualVersion = amendmentService.approve(manual.getId(), "procurement", false);
        PolicyVersion extractionVersion = amendmentService.approve(extraction.getId(), "procurement", false);
        assertThat(extractionVersion.getContentHash()).isEqualTo(manualVersion.getContentHash());

        Decision decision = run(ITEM, new BigDecimal("760"), PLANT, "2026-12-05", "Normal");
        assertThat(decision.getPolicyVersion()).isEqualTo(extractionVersion.getLabel());
    }

    @Test
    void p10EarlierRowsKeepBaselineWhileEligibleRowsUseAmendedVersion() {
        long versionsBefore = versionRepository.count();
        PolicyAmendment amendment = amendment(LocalDate.parse("2026-11-10"),
                operation("set_parameter", "key", "spot_limit", "value", 100000));
        amendmentService.preview(amendment.getId());
        PolicyVersion version = amendmentService.approve(amendment.getId(), "procurement", false);
        assertThat(versionRepository.count()).isEqualTo(versionsBefore + 1);

        Decision earlier = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-05", "Normal");
        Decision eligible = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-18", "Normal");
        assertThat(earlier.getPolicyVersion()).isEqualTo("v5.0");
        assertThat(eligible.getPolicyVersion()).isEqualTo(version.getLabel());
        assertThat(earlier.getRoute()).isEqualTo("SPOT_PO");
        assertThat(eligible.getRoute()).isEqualTo("RFQ");
    }

    @Test
    void p5ScopedSpotLimitOnlyAppliesToMatchingCategory() {
        Decision baseline = run("ITM-1050", new BigDecimal("1200"), "PUN1", "2026-11-18", "Normal");
        assertThat(baseline.getRoute()).isEqualTo("SPOT_PO");
        assertThat(money(baseline.getUnitPrice())).isEqualTo("130.83");
        assertThat(money(baseline.getGroupValueInr())).isEqualTo("155460.00");

        PolicyAmendment amendment = amendment(LocalDate.parse("2026-11-18"),
                operation("set_parameter", "key", "spot_limit", "value", 100000,
                        "scope_json", Map.of("category", "Safety & PPE")));
        amendmentService.preview(amendment.getId());
        amendmentService.approve(amendment.getId(), "procurement", false);

        Decision safety = run("ITM-1050", new BigDecimal("1200"), "PUN1", "2026-11-18", "Normal");
        Decision other = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-18", "Normal");
        assertThat(safety.getRoute()).isEqualTo("RFQ");
        assertThat(other.getRoute()).isEqualTo("SPOT_PO");
    }

    @Test
    void synthetic365DayNoContractCaseUsesInactivePoAndFailsActiveHistory() {
        contractLineRepository.deleteAll();
        contractRepository.deleteAll();

        Decision day369 = run("ITM-1049", new BigDecimal("2"), "CHK1", "2026-11-08", "Normal");
        Decision day375 = run("ITM-1049", new BigDecimal("2"), "CHK1", "2026-11-14", "Normal");

        assertThat(day369.getRoute()).isEqualTo("RFQ");
        assertThat(day375.getRoute()).isEqualTo("RFQ");
        assertThat(money(day369.getGroupValueInr())).isEqualTo("435.62");
        assertThat(money(day375.getGroupValueInr())).isEqualTo("435.62");
        assertThat(day369.getReason()).contains("7300332");
        assertThat(day375.getReason()).contains("7300332");
    }

    @Test
    void sameDatePoHistoryTieBreaksByPoNumber() {
        Decision decision = run("ITM-1039", new BigDecimal("10"), PLANT, "2026-09-23", "Normal");
        assertThat(decision.getRoute()).isEqualTo("SPOT_PO");
        assertThat(money(decision.getGroupValueInr())).isEqualTo("26559.20");
        assertThat(decision.getReason()).contains("7300265");
    }

    @Test
    void consolidationHoursComeFromEffectivePolicy() {
        EngineResult baseline = runBatch(List.of(
                consolidationLine("2026-11-05T09:00", "2026-11-15"),
                consolidationLine("2026-11-06T10:00", "2026-11-25")));
        assertThat(baseline.poGroups()).hasSize(2);

        PolicyAmendment amendment = amendment(LocalDate.parse("2026-11-18"),
                operation("set_parameter", "key", "consolidation_hours", "value", 48));
        amendmentService.preview(amendment.getId());
        PolicyVersion version = amendmentService.approve(amendment.getId(), "procurement", false);

        EngineResult amended = runBatch(List.of(
                consolidationLine("2026-11-18T09:00", "2026-11-28"),
                consolidationLine("2026-11-19T10:00", "2026-12-08")));
        assertThat(amended.poGroups()).hasSize(1);
        assertThat(amended.decisions())
                .allMatch(decision -> version.getLabel().equals(decision.policyVersion()));
    }

    @Test
    void repeatedRunsProduceIdenticalDecisions() {
        Decision first = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-05", "Normal");
        Decision second = run(ITEM, new BigDecimal("760"), PLANT, "2026-11-05", "Normal");

        assertThat(second.getRoute()).isEqualTo(first.getRoute());
        assertThat(second.getSupplierId()).isEqualTo(first.getSupplierId());
        assertThat(second.getUnitPrice()).isEqualByComparingTo(first.getUnitPrice());
        assertThat(second.getGroupValueInr()).isEqualByComparingTo(first.getGroupValueInr());
        assertThat(second.getSuggestedSupplierIds()).isEqualTo(first.getSuggestedSupplierIds());
        assertThat(second.getPolicyVersion()).isEqualTo(first.getPolicyVersion());
        assertThat(second.getClause()).isEqualTo(first.getClause());
        assertThat(second.getReason()).isEqualTo(first.getReason());
    }

    @Test
    void emergencyLimitIsInclusiveAndJustAboveFallsToRfq() {
        Decision atLimit = run(ITEM, quantityFor("500000.00"), PLANT, "2026-11-18", "Emergency");
        assertThat(atLimit.getRoute()).as("value=%s", atLimit.getGroupValueInr()).isEqualTo("EMERGENCY_PO");
        assertThat(money(atLimit.getGroupValueInr())).isEqualTo("500000.00");

        Decision above = run(ITEM, quantityFor("500000.02"), PLANT, "2026-11-18", "Emergency");
        assertThat(above.getRoute()).isEqualTo("RFQ");
    }

    @Test
    void spotLimitIsStrictAndBoundaryFallsToRfq() {
        Decision below = run(ITEM, quantityFor("199999.99"), PLANT, "2026-11-18", "Normal");
        assertThat(below.getRoute()).isEqualTo("SPOT_PO");

        Decision atBoundary = run(ITEM, quantityFor("200000.00"), PLANT, "2026-11-18", "Normal");
        assertThat(atBoundary.getRoute()).isEqualTo("RFQ");
    }

    @Test
    void rfqRfpBoundaryIsInclusiveForRfp() {
        Decision below = run(ITEM, quantityFor("999999.99"), PLANT, "2026-11-18", "Normal");
        assertThat(below.getRoute()).as("value=%s", below.getGroupValueInr()).isEqualTo("RFQ");

        Decision atBoundary = run(ITEM, quantityFor("1000000.00"), PLANT, "2026-11-18", "Normal");
        assertThat(atBoundary.getRoute()).isEqualTo("RFP");
    }

    private EngineResult runBatch(List<PrLineInput> lines) {
        return batchService.processInputs(lines, "acceptance-test", "test").result();
    }

    private PrLineInput consolidationLine(String createdAt, String needByDate) {
        long id = SEQUENCE.incrementAndGet();
        return new PrLineInput(
                id,
                "PR-CON-" + id,
                1,
                LocalDateTime.parse(createdAt),
                "test-buyer",
                "Maintenance",
                PLANT,
                ITEM,
                ITEM,
                new BigDecimal("760"),
                UOM,
                null,
                LocalDate.parse(needByDate),
                "Normal"
        );
    }

    private Decision run(String itemCode, BigDecimal quantity, String plant, String date, String urgency) {
        long id = SEQUENCE.incrementAndGet();
        LocalDate requirementDate = LocalDate.parse(date);
        PrLineInput line = new PrLineInput(
                id,
                "PR-ACC-" + id,
                1,
                requirementDate.atTime(9, 0),
                "test-buyer",
                "Maintenance",
                plant,
                itemCode,
                itemCode,
                quantity,
                uomFor(itemCode),
                null,
                requirementDate.plusDays(10),
                urgency
        );
        BatchService.BatchResponse response = batchService.processInputs(List.of(line), "acceptance-test", "test");
        List<Decision> decisions = decisionRepository.findByRunId(response.runId());
        if (decisions.isEmpty()) {
            throw new AssertionError("No stored decisions for run " + response.runId()
                    + "; engine decisions=" + response.result().decisions().size()
                    + "; lines=" + response.result().lines());
        }
        return decisions.get(0);
    }

    private String uomFor(String itemCode) {
        return ITEM.equals(itemCode) ? UOM : "NOS";
    }

    private BigDecimal quantityFor(String value) {
        return new BigDecimal(value).divide(new BigDecimal("166.41"), 12, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private PolicyAmendment amendment(LocalDate effectiveFrom, Map<String, Object>... operations) {
        return amendmentService.create("manual", "acceptance test", null, effectiveFrom,
                List.of(operations), "tester");
    }

    private Map<String, Object> operation(String type, Object... pairs) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", type);
        for (int i = 0; i < pairs.length; i += 2) {
            operation.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return operation;
    }
}
