package com.corvo.procurement;

import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.LineResult;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.ingestion.CsvExporter;
import com.corvo.procurement.ranking.PreferredSupplierService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenExamplesTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(970_000);

    @Autowired
    private BatchService batchService;

    @Autowired
    private PreferredSupplierService preferredSupplierService;

    @Autowired
    private CsvExporter csvExporter;

    private EngineResult result;

    @BeforeAll
    void processSample() {
        result = batchService.processSample("test").result();
    }

    @Test
    void sampleBatchHas186RowsAndExactlyTwoNotProcessed() {
        assertThat(result.lines()).hasSize(186);
        List<LineResult> notProcessed = result.lines().stream()
                .filter(line -> "NOT_PROCESSED".equals(line.route()))
                .toList();
        assertThat(notProcessed).hasSize(2);
        assertThat(notProcessed).extracting(LineResult::prNo)
                .containsExactlyInAnyOrder("PR-26-00172", "PR-26-00173");
        assertThat(notProcessed).extracting(LineResult::reason)
                .containsExactlyInAnyOrder("invalid quantity", "unknown plant");
    }

    @Test
    void workedExamples1To14Match() {
        assertLine("PR-26-00019", 1, "ITM-1002", "CONTRACT_PO", "S001", "9168.71", "137530.65", "PO-0012", null);
        assertLine("PR-26-00045", 1, "ITM-1037", "CONTRACT_PO", "S021", "2439.01", "26829.11", "PO-0073", null);
        assertLine("PR-26-00004", 1, "ITM-1022", "RFQ", null, null, "220255.60", null, "S014;S013;S016");
        assertLine("PR-26-00034", 1, "ITM-1009", "CONTRACT_PO", "S007", "7.06", "32546.60", "PO-0004", null);
        assertLine("PR-26-00146", 1, "ITM-1038", "SPOT_PO", "S024", "166.41", "126471.60", "PO-0002", null);
        assertLine("PR-26-00106", 1, "ITM-1044", "SPOT_PO", "S027", "21.59", "156170.30", "PO-0003", null);
        assertLine("PR-26-00103", 1, "ITM-1044", "SPOT_PO", "S027", "21.59", "137311.20", "PO-0019", null);
        assertLine("PR-26-00088", 1, "ITM-1035", "SPOT_PO", "S023", "391.82", "97955.00", "PO-0024", null);
        assertLine("PR-26-00059", 1, "ITM-1026", "EMERGENCY_PO", "S014", "106.38", "253184.40", "PO-0025", null);
        assertLine("PR-26-00089", 1, "ITM-1066", "RFQ", null, null, "336562.15", null, "S043;S044;S042");
        assertLine("PR-26-00050", 1, "ITM-1006", "RFQ", null, null, "80300.00", null, "S003;S002;S005");
        assertLine("PR-26-00100", 1, "ITM-1034", "RFP", null, null, "2070780.00", null, "S020;S017;S019;S018");
        assertLine("PR-26-00047", 1, "ITM-1071", "CAPEX_SOURCING", null, null, "6036704.08", null,
                "S045;S047;S020;S048;S046");
        assertLine("PR-26-00170", 1, "NEW", "NEW_ITEM_SOURCING", null, null, "141100.00", null, "");
        assertLine("PR-26-00031", 1, "ITM-1009", "CONTRACT_PO", "S007", "7.06", "23227.40", "PO-0004", null);
    }

    @Test
    void edgeCasesMatch() {
        assertLine("PR-26-00069", 1, "ITM-1017", "RFQ", null, null, "565000.92", null, null);
        assertLine("PR-26-00122", 1, "ITM-1020", "RFQ", null, null, "871086.00", null, null);
        assertLine("PR-26-00021", 1, "ITM-1049", "CONTRACT_PO", "S029", "191.31", "47827.50", null, null);
        assertLine("PR-26-00159", 1, "ITM-1049", "CONTRACT_PO", "S029", "191.31", "22957.20", null, null);
        assertLine("PR-26-00007", 1, "ITM-1014", "RFQ", null, null, "201446.40", null, null);
        assertLine("PR-26-00041", 1, "ITM-1039", "RFQ", null, null, "231497.60", null, null);
        assertLine("PR-26-00167", 2, "ITM-1073", "CAPEX_SOURCING", null, null, "1150000.00", null,
                "S045;S047;S020;S048;S046");
    }

    @Test
    void itm1003ContractPrecedenceOverPoHistory() {
        assertLine("PR-26-00012", 1, "ITM-1003", "CONTRACT_PO", "S001", "83.69", "98754.20", null, null);
    }

    @Test
    void itm1039PdfContractActiveOnStartDate() {
        long id = SEQUENCE.incrementAndGet();
        LocalDate requirementDate = LocalDate.parse("2026-11-15");
        PrLineInput line = new PrLineInput(
                id,
                "PR-GOLD-" + id,
                1,
                requirementDate.atTime(9, 0),
                "test-buyer",
                "Maintenance",
                "PUN1",
                "ITM-1039",
                "FRL unit 1/2 inch",
                new BigDecimal("80"),
                "NOS",
                null,
                requirementDate.plusDays(14),
                "Normal"
        );

        EngineResult synthetic = batchService.processInputs(List.of(line), "golden-itm-1039", "test").result();
        assertThat(synthetic.lines()).hasSize(1);
        LineResult result = synthetic.lines().get(0);
        assertThat(result.route()).isEqualTo("CONTRACT_PO");
        assertThat(result.supplierId()).isEqualTo("S021");
        assertThat(money(result.unitPrice())).isEqualTo("2746.87");
    }

    @Test
    void preferredSupplierGoldenListsMatch() {
        LocalDate asOf = LocalDate.parse("2026-11-01");
        assertPreferred("ITM-1048", asOf, "S029", "S030");
        assertPreferred("ITM-1036", asOf, "S023", "S022", "S024");
        assertPreferred("ITM-1016", asOf, "S009", "S010");
    }

    @Test
    void csvHasExactHeaderAnd186Rows() {
        String csv = csvExporter.export(result.lines());
        String[] lines = csv.split("\\R");
        assertThat(lines[0]).isEqualTo(CsvExporter.HEADER);
        assertThat(lines).hasSize(187);
        assertThat(lines[0].split(",", -1)).hasSize(13);
    }

    private void assertLine(String prNo, int prLine, String resolvedItem, String route, String supplierId,
                            String unitPrice, String groupValue, String poId, String suggestions) {
        LineResult line = result.lines().stream()
                .filter(l -> prNo.equals(l.prNo()) && Integer.valueOf(prLine).equals(l.prLine()))
                .findFirst()
                .orElseThrow();
        assertThat(line.resolvedItemCode()).isEqualTo(resolvedItem);
        assertThat(line.route()).isEqualTo(route);
        if (supplierId != null) {
            assertThat(line.supplierId()).isEqualTo(supplierId);
        }
        if (unitPrice != null) {
            assertThat(money(line.unitPrice())).isEqualTo(unitPrice);
        }
        if (groupValue != null) {
            assertThat(money(line.groupValueInr())).isEqualTo(groupValue);
        }
        if (poId != null) {
            assertThat(line.poId()).isEqualTo(poId);
        }
        if (suggestions != null) {
            assertThat(line.suggestedSupplierIds()).isEqualTo(suggestions);
        }
    }

    private void assertPreferred(String itemCode, LocalDate asOf, String... expected) {
        List<PreferredSupplier> suppliers = preferredSupplierService.listForItem(asOf, itemCode);
        assertThat(suppliers.stream().map(PreferredSupplier::supplierId).toList())
                .containsExactly(Arrays.copyOf(expected, expected.length));
    }

    private String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
