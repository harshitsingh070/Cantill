package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.ContractCandidate;
import com.corvo.procurement.engine.model.ContractInfo;
import com.corvo.procurement.engine.model.ContractLineInfo;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.PoRecord;
import com.corvo.procurement.engine.model.SupplierInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ContractEvaluator {
    private final EngineData data;
    private final Map<String, BigDecimal> consumed;

    public ContractEvaluator(EngineData data) {
        this.data = data;
        this.consumed = new HashMap<>();
        for (ContractInfo contract : data.contracts()) {
            consumed.put(contract.contractId(), contract.consumedValue() == null ? BigDecimal.ZERO : contract.consumedValue());
        }
    }

    public List<ContractCandidate> applicableContracts(String itemCode, LocalDate requirementDate, BigDecimal quantity) {
        List<ContractCandidate> candidates = new ArrayList<>();
        if (itemCode == null || requirementDate == null || quantity == null) {
            return candidates;
        }
        for (ContractInfo contract : data.contracts()) {
            SupplierInfo supplier = data.suppliers().get(contract.supplierId());
            if (supplier == null || !supplier.isActive()) {
                continue;
            }
            if (requirementDate.isBefore(contract.startDate()) || requirementDate.isAfter(contract.endDate())) {
                continue;
            }
            ContractLineInfo line = contract.lineFor(itemCode);
            if (line == null) {
                continue;
            }
            BigDecimal prospective = quantity.multiply(line.rate());
            BigDecimal used = consumed.getOrDefault(contract.contractId(), BigDecimal.ZERO);
            boolean usable = used.add(prospective).compareTo(contract.valueCap()) <= 0;
            candidates.add(new ContractCandidate(contract, line, prospective, usable));
        }
        candidates.sort(Comparator
                .comparing((ContractCandidate c) -> c.line().rate())
                .thenComparing(c -> c.contract().contractId()));
        return candidates;
    }

    public ValuationResult valueNonCapex(ItemInfo item, LocalDate requirementDate, BigDecimal quantity, int valuationHistoryDays) {
        List<ContractCandidate> applicable = applicableContracts(item.itemCode(), requirementDate, quantity);
        if (!applicable.isEmpty()) {
            Optional<ContractCandidate> usable = applicable.stream().filter(ContractCandidate::usable).findFirst();
            if (usable.isPresent()) {
                ContractCandidate selected = usable.get();
                return new ValuationResult(
                        selected.line().rate(),
                        selected.contract().contractId(),
                        "contract " + selected.contract().contractId() + " at " + selected.line().rate(),
                        applicable,
                        true
                );
            }
            ContractCandidate lowest = applicable.get(0);
            return new ValuationResult(
                    lowest.line().rate(),
                    null,
                    "contract rate " + lowest.contract().contractId() + " at " + lowest.line().rate() + " (ceiling unavailable)",
                    applicable,
                    false
            );
        }
        return valueWithoutContract(item, requirementDate, valuationHistoryDays);
    }

    public ValuationResult valueCapex(ItemInfo item, LocalDate requirementDate, int valuationHistoryDays) {
        return valueWithoutContract(item, requirementDate, valuationHistoryDays);
    }

    private ValuationResult valueWithoutContract(ItemInfo item, LocalDate requirementDate, int valuationHistoryDays) {
        LocalDate from = requirementDate.minusDays(valuationHistoryDays);
        Optional<PoRecord> recentPo = data.poHistory().stream()
                .filter(po -> po.itemCode().equals(item.itemCode()))
                .filter(po -> !po.poDate().isBefore(from) && !po.poDate().isAfter(requirementDate))
                .max(Comparator.comparing(PoRecord::poDate).thenComparing(po -> poNumberKey(po.poNumber())));
        if (recentPo.isPresent()) {
            PoRecord po = recentPo.get();
            return new ValuationResult(po.unitPrice(), null,
                    "PO " + po.poNumber() + " dated " + po.poDate() + " at " + po.unitPrice(),
                    List.of(), false);
        }
        return new ValuationResult(item.standardPrice(), null,
                "standard price at " + item.standardPrice(), List.of(), false);
    }

    public boolean hasActiveHistory(String itemCode, LocalDate requirementDate, int activeHistoryDays) {
        LocalDate from = requirementDate.minusDays(activeHistoryDays);
        return data.poHistory().stream()
                .filter(po -> po.itemCode().equals(itemCode))
                .filter(po -> !po.poDate().isBefore(from) && !po.poDate().isAfter(requirementDate))
                .anyMatch(po -> {
                    SupplierInfo supplier = data.suppliers().get(po.supplierId());
                    return supplier != null && supplier.isActive();
                });
    }

    public void consume(String contractId, BigDecimal value) {
        if (contractId == null || value == null) {
            return;
        }
        consumed.merge(contractId, value, BigDecimal::add);
    }

    public BigDecimal consumedFor(String contractId) {
        return consumed.getOrDefault(contractId, BigDecimal.ZERO);
    }

    public static String poNumberKey(String poNumber) {
        if (poNumber == null) {
            return "";
        }
        return String.format("%20s", poNumber).replace(' ', '0');
    }

    public static BigDecimal roundMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public record ValuationResult(
            BigDecimal unitPrice,
            String contractId,
            String source,
            List<ContractCandidate> applicableContracts,
            boolean usableContract
    ) {
    }
}
