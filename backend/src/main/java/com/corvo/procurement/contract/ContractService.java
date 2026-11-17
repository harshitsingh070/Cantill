package com.corvo.procurement.contract;

import com.corvo.procurement.ai.AiClient;
import com.corvo.procurement.domain.Contract;
import com.corvo.procurement.domain.ContractConsumptionRun;
import com.corvo.procurement.domain.ContractConsumptionRunRepository;
import com.corvo.procurement.domain.ContractLine;
import com.corvo.procurement.domain.ContractLineRepository;
import com.corvo.procurement.domain.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ContractService {
    private final ContractRepository contractRepository;
    private final ContractLineRepository contractLineRepository;
    private final ContractConsumptionRunRepository consumptionRunRepository;
    private final AiClient aiClient;

    public ContractService(ContractRepository contractRepository,
                           ContractLineRepository contractLineRepository,
                           ContractConsumptionRunRepository consumptionRunRepository,
                           AiClient aiClient) {
        this.contractRepository = contractRepository;
        this.contractLineRepository = contractLineRepository;
        this.consumptionRunRepository = consumptionRunRepository;
        this.aiClient = aiClient;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, BigDecimal> runConsumption = consumptionRunRepository.findAll().stream()
                .collect(Collectors.groupingBy(ContractConsumptionRun::getContractId,
                        Collectors.reducing(BigDecimal.ZERO, ContractConsumptionRun::getAmount, BigDecimal::add)));
        return contractRepository.findAll().stream()
                .sorted((a, b) -> a.getContractId().compareTo(b.getContractId()))
                .map(contract -> present(contract, runConsumption.getOrDefault(contract.getContractId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String contractId) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));
        BigDecimal run = consumptionRunRepository.findAll().stream()
                .filter(c -> contractId.equals(c.getContractId()))
                .map(ContractConsumptionRun::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return present(contract, run);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Map<String, Object> body) {
        String contractId = string(firstNonNull(body.get("contract_id"), body.get("contractId")));
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("contract_id is required");
        }
        Contract contract = contractRepository.findById(contractId).orElseGet(Contract::new);
        contract.setContractId(contractId);
        contract.setSupplierId(string(firstNonNull(body.get("supplier_id"), body.get("supplierId"))));
        contract.setSupplierName(string(firstNonNull(body.get("supplier_name"), body.get("supplierName"))));
        contract.setStartDate(date(firstNonNull(body.get("start_date"), body.get("startDate"))));
        contract.setEndDate(date(firstNonNull(body.get("end_date"), body.get("endDate"))));
        contract.setValueCap(decimal(firstNonNull(body.get("value_cap"), body.get("valueCap"))));
        Object consumedValue = firstNonNull(body.get("consumed_value"), body.get("consumedValue"));
        if (consumedValue != null) {
            contract.setConsumedValue(decimal(consumedValue));
        } else if (contract.getConsumedValue() == null) {
            contract.setConsumedValue(java.math.BigDecimal.ZERO);
        }
        contract.setSource(body.get("source") == null ? "manual" : string(body.get("source")));
        contract.setStatus(body.get("status") == null ? "Active" : string(body.get("status")));
        contract = contractRepository.save(contract);

        if (body.get("lines") instanceof List<?> lines) {
            contractLineRepository.deleteAll(contractLineRepository.findByContractId(contractId));
            List<ContractLine> saved = new ArrayList<>();
            for (Object value : lines) {
                if (!(value instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> line = new LinkedHashMap<>();
                raw.forEach((k, v) -> line.put(String.valueOf(k), v));
                ContractLine entity = new ContractLine();
                entity.setContractId(contractId);
                entity.setItemCode(string(firstNonNull(line.get("item_code"), line.get("itemCode"))));
                entity.setDescription(string(line.get("description")));
                entity.setUom(string(line.get("uom")));
                entity.setRate(decimal(line.get("rate")));
                saved.add(entity);
            }
            contractLineRepository.saveAll(saved);
        }
        return get(contractId);
    }

    public Map<String, Object> extract(byte[] bytes, String filename) {
        return aiClient.upload("/contract/extract", bytes, filename, Map.of());
    }

    private Map<String, Object> present(Contract contract, BigDecimal runConsumption) {
        BigDecimal baseline = contract.getConsumedValue() == null ? BigDecimal.ZERO : contract.getConsumedValue();
        BigDecimal consumed = baseline.add(runConsumption == null ? BigDecimal.ZERO : runConsumption);
        BigDecimal remaining = contract.getValueCap() == null ? null : contract.getValueCap().subtract(consumed);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_id", contract.getContractId());
        body.put("supplier_id", contract.getSupplierId());
        body.put("supplier_name", contract.getSupplierName());
        body.put("start_date", contract.getStartDate());
        body.put("end_date", contract.getEndDate());
        body.put("value_cap", contract.getValueCap());
        body.put("baseline_consumed", baseline);
        body.put("run_consumption", runConsumption == null ? BigDecimal.ZERO : runConsumption);
        body.put("consumed", consumed);
        body.put("remaining", remaining);
        body.put("source", contract.getSource());
        body.put("status", contract.getStatus());
        body.put("lines", contractLineRepository.findByContractId(contract.getContractId()));
        return body;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
