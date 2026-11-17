package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.ContractCandidate;
import com.corvo.procurement.engine.model.ContractInfo;
import com.corvo.procurement.engine.model.ContractLineInfo;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.SupplierInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractEvaluatorTest {
    @Test
    void equalRatesPreferLatestStartThenHighestContractId() {
        SupplierInfo supplier = new SupplierInfo(
                "S1", "Supplier", "Pune", List.of(), "Active",
                new BigDecimal("0.95"), new BigDecimal("4.50"));
        ItemInfo item = new ItemInfo(
                "ITM-TEST", "Test item", "NOS", "Maintenance", "Consumable",
                new BigDecimal("10.00"));

        ContractInfo oldContract = contract("C-OLD", "2025-01-01");
        ContractInfo newLowerId = contract("C-NEW-A", "2026-01-01");
        ContractInfo newHigherId = contract("C-NEW-B", "2026-01-01");

        EngineData data = new EngineData(
                Map.of(item.itemCode(), item),
                Map.of(supplier.supplierId(), supplier),
                List.of(),
                List.of(),
                List.of(oldContract, newLowerId, newHigherId),
                List.of(),
                List.of()
        );

        List<ContractCandidate> candidates = new ContractEvaluator(data).applicableContracts(
                item.itemCode(), LocalDate.parse("2026-06-01"), BigDecimal.TEN);

        assertThat(candidates)
                .extracting(candidate -> candidate.contract().contractId())
                .containsExactly("C-NEW-B", "C-NEW-A", "C-OLD");
    }

    private ContractInfo contract(String contractId, String startDate) {
        return new ContractInfo(
                contractId,
                "S1",
                "Supplier",
                LocalDate.parse(startDate),
                LocalDate.parse("2027-12-31"),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                "test",
                List.of(new ContractLineInfo(
                        "ITM-TEST", "Test item", "NOS", new BigDecimal("10.00")))
        );
    }
}
