package com.corvo.procurement.ranking;

import com.corvo.procurement.engine.EngineDataFactory;
import com.corvo.procurement.engine.PreferredSupplierEngine;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.policy.ParameterResolver;
import com.corvo.procurement.policy.PolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class PreferredSupplierService {
    private final EngineDataFactory engineDataFactory;
    private final PolicyService policyService;

    public PreferredSupplierService(EngineDataFactory engineDataFactory, PolicyService policyService) {
        this.engineDataFactory = engineDataFactory;
        this.policyService = policyService;
    }

    public Map<String, List<PreferredSupplier>> list(LocalDate asOfDate, String itemCode) {
        PolicySnapshot policy = policyService.selectSnapshot(asOfDate)
                .orElseThrow(() -> new IllegalStateException("No policy effective for as-of date " + asOfDate));
        EngineData data = engineDataFactory.build();
        PreferredSupplierEngine engine = new PreferredSupplierEngine(data);
        ParameterResolver resolver = new ParameterResolver(policy.parameters());
        Map<String, List<PreferredSupplier>> result = new LinkedHashMap<>();
        List<ItemInfo> items = new ArrayList<>(data.items().values());
        items.sort(java.util.Comparator.comparing(ItemInfo::itemCode));
        for (ItemInfo item : items) {
            if (itemCode != null && !itemCode.isBlank() && !item.itemCode().equals(itemCode)) {
                continue;
            }
            result.put(item.itemCode(), engine.computeWithFallback(item, asOfDate, policy, resolver));
        }
        return result;
    }

    public List<PreferredSupplier> listForItem(LocalDate asOfDate, String itemCode) {
        return list(asOfDate, itemCode).getOrDefault(itemCode, List.of());
    }

    public String exportTopThree(LocalDate asOfDate, String itemCode) {
        Map<String, List<PreferredSupplier>> all = list(asOfDate, itemCode);
        StringBuilder csv = new StringBuilder("item_code,rank,supplier_id,reference_price\n");
        for (Map.Entry<String, List<PreferredSupplier>> entry : all.entrySet()) {
            int count = 0;
            for (PreferredSupplier supplier : entry.getValue()) {
                if (count++ >= 3) {
                    break;
                }
                csv.append(entry.getKey()).append(',')
                        .append(supplier.rank()).append(',')
                        .append(supplier.supplierId()).append(',')
                        .append(money(supplier.referencePrice())).append('\n');
            }
        }
        return csv.toString();
    }

    private String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
