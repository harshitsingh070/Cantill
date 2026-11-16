package com.corvo.procurement.engine.model;

import java.util.List;
import java.util.Map;

public record EngineData(
        Map<String, ItemInfo> items,
        Map<String, SupplierInfo> suppliers,
        List<PlantInfo> plants,
        List<PoRecord> poHistory,
        List<ContractInfo> contracts,
        List<AliasInfo> aliases,
        List<PolicySnapshot> policies
) {
}
