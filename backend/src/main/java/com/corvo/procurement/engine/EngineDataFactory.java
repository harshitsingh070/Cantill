package com.corvo.procurement.engine;

import com.corvo.procurement.domain.Contract;
import com.corvo.procurement.domain.ContractLine;
import com.corvo.procurement.domain.ContractLineRepository;
import com.corvo.procurement.domain.ContractRepository;
import com.corvo.procurement.domain.Item;
import com.corvo.procurement.domain.ItemAlias;
import com.corvo.procurement.domain.ItemAliasRepository;
import com.corvo.procurement.domain.ItemRepository;
import com.corvo.procurement.domain.Plant;
import com.corvo.procurement.domain.PlantRepository;
import com.corvo.procurement.domain.PoHistory;
import com.corvo.procurement.domain.PoHistoryRepository;
import com.corvo.procurement.domain.Supplier;
import com.corvo.procurement.domain.SupplierRepository;
import com.corvo.procurement.engine.model.AliasInfo;
import com.corvo.procurement.engine.model.ContractInfo;
import com.corvo.procurement.engine.model.ContractLineInfo;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.PlantInfo;
import com.corvo.procurement.engine.model.PoRecord;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.SupplierInfo;
import com.corvo.procurement.policy.PolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class EngineDataFactory {
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final PlantRepository plantRepository;
    private final PoHistoryRepository poHistoryRepository;
    private final ItemAliasRepository itemAliasRepository;
    private final ContractRepository contractRepository;
    private final ContractLineRepository contractLineRepository;
    private final PolicyService policyService;

    public EngineDataFactory(ItemRepository itemRepository,
                             SupplierRepository supplierRepository,
                             PlantRepository plantRepository,
                             PoHistoryRepository poHistoryRepository,
                             ItemAliasRepository itemAliasRepository,
                             ContractRepository contractRepository,
                             ContractLineRepository contractLineRepository,
                             PolicyService policyService) {
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.plantRepository = plantRepository;
        this.poHistoryRepository = poHistoryRepository;
        this.itemAliasRepository = itemAliasRepository;
        this.contractRepository = contractRepository;
        this.contractLineRepository = contractLineRepository;
        this.policyService = policyService;
    }

    public EngineData build() {
        Map<String, ItemInfo> items = new LinkedHashMap<>();
        for (Item item : itemRepository.findAll()) {
            items.put(item.getItemCode(), new ItemInfo(
                    item.getItemCode(),
                    item.getDescription(),
                    item.getUom(),
                    item.getCategory(),
                    item.getItemType(),
                    item.getStandardPrice()
            ));
        }
        Map<String, SupplierInfo> suppliers = new LinkedHashMap<>();
        for (Supplier supplier : supplierRepository.findAll()) {
            suppliers.put(supplier.getSupplierId(), new SupplierInfo(
                    supplier.getSupplierId(),
                    supplier.getName(),
                    supplier.getCity(),
                    categories(supplier.getCategories()),
                    supplier.getStatus(),
                    supplier.getOnTimePct(),
                    supplier.getQualityRating()
            ));
        }
        List<PlantInfo> plants = plantRepository.findAll().stream()
                .map(p -> new PlantInfo(p.getCode(), p.getName()))
                .toList();
        List<PoRecord> poHistory = poHistoryRepository.findAll().stream()
                .map(po -> new PoRecord(
                        po.getPoNumber(),
                        po.getPoDate(),
                        po.getSupplierId(),
                        po.getItemCode(),
                        po.getQuantity(),
                        po.getUom(),
                        po.getUnitPrice(),
                        po.getPlant(),
                        po.getDeliveredOnTime()
                ))
                .toList();
        Map<String, List<ContractLineInfo>> linesByContract = contractLineRepository.findAll().stream()
                .collect(Collectors.groupingBy(ContractLine::getContractId, LinkedHashMap::new,
                        Collectors.mapping(line -> new ContractLineInfo(
                                line.getItemCode(),
                                line.getDescription(),
                                line.getUom(),
                                line.getRate()
                        ), Collectors.toList())));
        List<ContractInfo> contracts = contractRepository.findAll().stream()
                .map(contract -> new ContractInfo(
                        contract.getContractId(),
                        contract.getSupplierId(),
                        contract.getSupplierName(),
                        contract.getStartDate(),
                        contract.getEndDate(),
                        contract.getValueCap(),
                        contract.getConsumedValue(),
                        contract.getSource(),
                        linesByContract.getOrDefault(contract.getContractId(), List.of())
                ))
                .toList();
        List<AliasInfo> aliases = itemAliasRepository.findAll().stream()
                .map(alias -> new AliasInfo(alias.getAlias(), alias.getCanonicalTerm()))
                .toList();
        List<PolicySnapshot> policies = policyService.allSnapshots();
        return new EngineData(items, suppliers, plants, poHistory, contracts, aliases, policies);
    }

    private List<String> categories(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(";")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }
}
