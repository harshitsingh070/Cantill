package com.corvo.procurement;

import com.corvo.procurement.engine.model.LineResult;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.ingestion.CsvExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ValidationWorkflowTest {
    private static final AtomicLong SEQUENCE = new AtomicLong(925_000);

    @Autowired
    private BatchService batchService;

    @Test
    void missingNeedByLineIsRetainedAsNotProcessedInCsv() {
        long id = SEQUENCE.incrementAndGet();
        PrLineInput line = new PrLineInput(
                id,
                "PR-NEED-" + id,
                1,
                LocalDateTime.parse("2026-11-10T09:00"),
                "test-buyer",
                "Maintenance",
                "CHK1",
                "ITM-1038",
                "ITM-1038",
                new BigDecimal("760"),
                "LTR",
                null,
                null,
                "Normal"
        );

        BatchService.BatchResponse response = batchService.processInputs(
                List.of(line), "validation-test", "test");

        assertThat(response.result().lines()).hasSize(1);
        LineResult result = response.result().lines().get(0);
        assertThat(result.route()).isEqualTo("NOT_PROCESSED");
        assertThat(result.validationStatus()).isEqualTo("NOT_PROCESSED");
        assertThat(result.reason()).contains("missing need-by date");

        List<String> rows = new CsvExporter()
                .export(response.result().lines())
                .lines()
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1))
                .contains("PR-NEED-" + id)
                .contains("NOT_PROCESSED")
                .contains("missing need-by date");
    }
}
