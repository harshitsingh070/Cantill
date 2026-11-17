package com.corvo.procurement.config;

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
import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyFactRepository;
import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyParameterRepository;
import com.corvo.procurement.domain.PolicyRule;
import com.corvo.procurement.domain.PolicyRuleRepository;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.domain.PolicyVersionRepository;
import com.corvo.procurement.domain.Supplier;
import com.corvo.procurement.domain.SupplierRepository;
import com.corvo.procurement.policy.PolicyHasher;
import com.corvo.procurement.policy.PolicyJson;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "corvo.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedRunner implements ApplicationRunner {
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final PlantRepository plantRepository;
    private final PoHistoryRepository poHistoryRepository;
    private final ItemAliasRepository itemAliasRepository;
    private final ContractRepository contractRepository;
    private final ContractLineRepository contractLineRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyFactRepository policyFactRepository;
    private final PolicyRuleRepository policyRuleRepository;
    private final PolicyParameterRepository policyParameterRepository;

    public SeedRunner(ItemRepository itemRepository,
                      SupplierRepository supplierRepository,
                      PlantRepository plantRepository,
                      PoHistoryRepository poHistoryRepository,
                      ItemAliasRepository itemAliasRepository,
                      ContractRepository contractRepository,
                      ContractLineRepository contractLineRepository,
                      PolicyVersionRepository policyVersionRepository,
                      PolicyFactRepository policyFactRepository,
                      PolicyRuleRepository policyRuleRepository,
                      PolicyParameterRepository policyParameterRepository) {
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.plantRepository = plantRepository;
        this.poHistoryRepository = poHistoryRepository;
        this.itemAliasRepository = itemAliasRepository;
        this.contractRepository = contractRepository;
        this.contractLineRepository = contractLineRepository;
        this.policyVersionRepository = policyVersionRepository;
        this.policyFactRepository = policyFactRepository;
        this.policyRuleRepository = policyRuleRepository;
        this.policyParameterRepository = policyParameterRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedItems();
        seedSuppliers();
        seedPlants();
        seedPoHistory();
        seedContracts();
        seedAliases();
        seedPolicy();
    }

    private void seedItems() {
        if (itemRepository.count() > 0) {
            return;
        }
        List<Item> items = new ArrayList<>();
        for (CSVRecord record : read("seed/items.csv")) {
            Item item = new Item();
            item.setItemCode(record.get("item_code"));
            item.setDescription(record.get("description"));
            item.setUom(record.get("uom"));
            item.setCategory(record.get("category"));
            item.setItemType(record.get("item_type"));
            item.setStandardPrice(decimal(record.get("standard_price")));
            items.add(item);
        }
        itemRepository.saveAll(items);
    }

    private void seedSuppliers() {
        if (supplierRepository.count() > 0) {
            return;
        }
        List<Supplier> suppliers = new ArrayList<>();
        for (CSVRecord record : read("seed/suppliers.csv")) {
            Supplier supplier = new Supplier();
            supplier.setSupplierId(record.get("supplier_id"));
            supplier.setName(record.get("name"));
            supplier.setCity(record.get("city"));
            supplier.setCategories(record.get("categories"));
            supplier.setStatus(record.get("status"));
            supplier.setOnTimePct(decimal(record.get("on_time_pct")));
            supplier.setQualityRating(decimal(record.get("quality_rating")));
            suppliers.add(supplier);
        }
        supplierRepository.saveAll(suppliers);
    }

    private void seedPlants() {
        if (plantRepository.count() > 0) {
            return;
        }
        List<Plant> plants = new ArrayList<>();
        for (CSVRecord record : read("seed/plants.csv")) {
            Plant plant = new Plant();
            plant.setCode(record.get("plant"));
            plant.setName(record.get("name"));
            plants.add(plant);
        }
        plantRepository.saveAll(plants);
    }

    private void seedPoHistory() {
        if (poHistoryRepository.count() > 0) {
            return;
        }
        List<PoHistory> rows = new ArrayList<>();
        for (CSVRecord record : read("seed/po_history.csv")) {
            PoHistory po = new PoHistory();
            po.setPoNumber(record.get("po_number"));
            po.setPoDate(LocalDate.parse(record.get("po_date")));
            po.setSupplierId(record.get("supplier_id"));
            po.setItemCode(record.get("item_code"));
            po.setQuantity(decimal(record.get("quantity")));
            po.setUom(record.get("uom"));
            po.setUnitPrice(decimal(record.get("unit_price")));
            po.setPlant(record.get("plant"));
            po.setDeliveredOnTime(Boolean.parseBoolean(record.get("delivered_on_time")));
            rows.add(po);
        }
        poHistoryRepository.saveAll(rows);
    }

    private void seedContracts() {
        if (contractRepository.count() > 0) {
            return;
        }
        List<Contract> contracts = new ArrayList<>();
        for (CSVRecord record : read("seed/contract_register.csv")) {
            Contract contract = new Contract();
            contract.setContractId(record.get("contract_id"));
            contract.setSupplierId(record.get("supplier_id"));
            contract.setSupplierName(record.get("supplier_name"));
            contract.setStartDate(LocalDate.parse(record.get("start_date")));
            contract.setEndDate(LocalDate.parse(record.get("end_date")));
            contract.setValueCap(decimal(record.get("value_cap")));
            contract.setConsumedValue(decimal(record.get("consumed_value")));
            contract.setSource("register");
            contract.setStatus("Active");
            contracts.add(contract);
        }
        Contract pdfOnly = new Contract();
        pdfOnly.setContractId("RC-2026-031");
        pdfOnly.setSupplierId("S021");
        pdfOnly.setSupplierName("Pneumatic Point");
        pdfOnly.setStartDate(LocalDate.parse("2026-11-15"));
        pdfOnly.setEndDate(LocalDate.parse("2027-11-14"));
        pdfOnly.setValueCap(new BigDecimal("2000000"));
        pdfOnly.setConsumedValue(BigDecimal.ZERO);
        pdfOnly.setSource("pdf");
        pdfOnly.setStatus("Active");
        contracts.add(pdfOnly);

        Contract pdfOnly2 = new Contract();
        pdfOnly2.setContractId("RC-2026-033");
        pdfOnly2.setSupplierId("S035");
        pdfOnly2.setSupplierName("Infoline Systems");
        pdfOnly2.setStartDate(LocalDate.parse("2026-11-20"));
        pdfOnly2.setEndDate(LocalDate.parse("2027-11-19"));
        pdfOnly2.setValueCap(new BigDecimal("5000000"));
        pdfOnly2.setConsumedValue(BigDecimal.ZERO);
        pdfOnly2.setSource("pdf");
        pdfOnly2.setStatus("Active");
        contracts.add(pdfOnly2);
        contractRepository.saveAll(contracts);

        List<ContractLine> lines = new ArrayList<>();
        for (CSVRecord record : read("seed/contract_lines.csv")) {
            ContractLine line = new ContractLine();
            line.setContractId(record.get("contract_id"));
            line.setItemCode(record.get("item_code"));
            line.setDescription(record.get("description"));
            line.setUom(record.get("uom"));
            line.setRate(decimal(record.get("rate")));
            lines.add(line);
        }
        addContractLine(lines, "RC-2026-031", "ITM-1036", "Pneumatic cylinder 50x100", "NOS", "3537.76");
        addContractLine(lines, "RC-2026-031", "ITM-1037", "Solenoid valve 5/2 1/4 inch", "NOS", "2439.01");
        addContractLine(lines, "RC-2026-031", "ITM-1039", "FRL unit 1/2 inch", "NOS", "2746.87");
        addContractLine(lines, "RC-2026-033", "ITM-1056", "Laptop i5 16GB 512GB", "NOS", "64654.36");
        addContractLine(lines, "RC-2026-033", "ITM-1057", "24 inch monitor", "NOS", "10689.51");
        contractLineRepository.saveAll(lines);
    }

    private void addContractLine(List<ContractLine> lines, String contractId, String itemCode,
                                 String description, String uom, String rate) {
        ContractLine line = new ContractLine();
        line.setContractId(contractId);
        line.setItemCode(itemCode);
        line.setDescription(description);
        line.setUom(uom);
        line.setRate(new BigDecimal(rate));
        lines.add(line);
    }

    private void seedAliases() {
        if (itemAliasRepository.count() > 0) {
            return;
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("brg", "bearing");
        aliases.put("pneu", "pneumatic");
        aliases.put("hyd", "hydraulic");
        aliases.put("welding rod", "welding electrode");
        aliases.put("belt", "v belt");
        aliases.put("goggle", "goggles");
        aliases.put("ear plug", "ear plugs");
        aliases.put("glove", "gloves");
        aliases.put("sprocket", "chain sprocket");
        aliases.put("ms", "mild steel");
        aliases.put("ss", "stainless steel");
        aliases.put("maint", "maintenance");
        aliases.put("cyl", "cylinder");
        aliases.put("lube", "lubricant");
        aliases.put("lubricating oil", "lubricant");
        aliases.put("mig wire", "mig wire");
        aliases.put("stick electrode", "welding electrode");
        List<ItemAlias> rows = new ArrayList<>();
        aliases.forEach((alias, canonical) -> {
            ItemAlias row = new ItemAlias();
            row.setAlias(alias);
            row.setCanonicalTerm(canonical);
            row.setLanguage("en");
            row.setSource("seed");
            rows.add(row);
        });
        itemAliasRepository.saveAll(rows);
    }

    private void seedPolicy() {
        if (policyVersionRepository.findByLabel("v5.0").isPresent()) {
            return;
        }
        PolicyVersion version = new PolicyVersion();
        version.setLabel("v5.0");
        version.setDocumentRef("CORVO-PROC-POL-04 v5.0");
        version.setEffectiveFrom(LocalDate.parse("2026-04-01"));
        version.setStatus("ACTIVE");
        version.setSource("seed");
        version.setContentHash("pending");
        version.setApprovedBy("system");
        version.setApprovedAt(LocalDateTime.parse("2026-03-25T00:00:00"));
        version.setActivatedAt(LocalDateTime.parse("2026-04-01T00:00:00"));
        version.setCreatedAt(LocalDateTime.parse("2026-03-25T00:00:00"));
        version.setNotes("CORVO-PROC-POL-04 version 5.0, effective 1 April 2026");
        version = policyVersionRepository.save(version);

        seedFacts();
        List<PolicyParameter> parameters = seedParameters(version.getId());
        List<PolicyRule> rules = seedRules(version.getId());
        version.setContentHash(PolicyHasher.hash(rules, parameters));
        policyVersionRepository.save(version);
    }

    private void seedFacts() {
        if (policyFactRepository.count() > 0) {
            return;
        }
        List<PolicyFact> facts = new ArrayList<>();
        facts.add(fact("item_code", "string", "item_field", "{\"field\":\"item_code\"}", "Matched item code, or NEW"));
        facts.add(fact("item_type", "enum", "item_field", "{\"field\":\"item_type\"}", "Standard, Material or Capex item type"));
        facts.add(fact("category", "string", "item_field", "{\"field\":\"category\"}", "Item category"));
        facts.add(fact("plant", "enum", "requirement_field", "{\"field\":\"plant\"}", "PUN1 or CHK1"));
        facts.add(fact("value_inr", "decimal", "requirement_field", "{\"field\":\"value_inr\"}", "Requirement value"));
        facts.add(fact("urgency", "enum", "requirement_field", "{\"field\":\"urgency\"}", "Normal or Emergency"));
        facts.add(fact("quantity", "decimal", "requirement_field", "{\"field\":\"quantity\"}", "Requirement quantity"));
        facts.add(fact("uom", "string", "item_field", "{\"field\":\"uom\"}", "Item unit of measure"));
        facts.add(fact("need_by_offset_days", "integer", "requirement_field",
                "{\"field\":\"need_by_date_minus_requirement_date\"}",
                "need_by_date minus requirement_date in calendar days; negative means past due"));
        facts.add(fact("has_applicable_contract", "boolean", "contract_flag",
                "{\"field\":\"has_applicable_contract\"}", "At least one applicable contract"));
        facts.add(fact("has_contract_with_ceiling", "boolean", "contract_flag",
                "{\"field\":\"has_contract_with_ceiling\"}", "At least one applicable contract has room for this requirement"));
        facts.add(fact("has_active_history", "boolean", "history_flag",
                "{\"window_param\":\"active_history_days\",\"supplier_status\":\"Active\"}",
                "Active supplier PO within active_history_days before the requirement"));
        facts.add(fact("is_new_item", "boolean", "requirement_field", "{\"field\":\"is_new_item\"}",
                "No confirmed item match"));
        policyFactRepository.saveAll(facts);
    }

    private PolicyFact fact(String key, String type, String resolver, String config, String description) {
        PolicyFact fact = new PolicyFact();
        fact.setFactKey(key);
        fact.setValueType(type);
        fact.setResolverKey(resolver);
        fact.setConfigJson(config);
        fact.setDescription(description);
        fact.setIsAvailable(true);
        return fact;
    }

    private List<PolicyParameter> seedParameters(Long versionId) {
        List<PolicyParameter> parameters = new ArrayList<>();
        parameter(parameters, versionId, "emergency_limit", "decimal", "500000");
        parameter(parameters, versionId, "spot_limit", "decimal", "200000");
        parameter(parameters, versionId, "rfq_rfp_boundary", "decimal", "1000000");
        parameter(parameters, versionId, "club_window_days", "integer", "14");
        parameter(parameters, versionId, "need_by_window_days", "integer", "7");
        parameter(parameters, versionId, "consolidation_hours", "integer", "24");
        parameter(parameters, versionId, "active_history_days", "integer", "365");
        parameter(parameters, versionId, "valuation_history_days", "integer", "365");
        parameter(parameters, versionId, "preferred_history_days", "integer", "548");
        parameter(parameters, versionId, "recency_fresh_days", "integer", "90");
        parameter(parameters, versionId, "recency_usable_days", "integer", "365");
        parameter(parameters, versionId, "suggestion_count_rfq", "integer", "3");
        parameter(parameters, versionId, "suggestion_count_rfp", "integer", "5");
        parameter(parameters, versionId, "suggestion_count_capex", "integer", "5");
        parameter(parameters, versionId, "item_match_threshold", "decimal", "0.50");
        parameter(parameters, versionId, "item_match_token_weight", "decimal", "0.50");
        parameter(parameters, versionId, "item_match_fuzzy_weight", "decimal", "0.50");
        parameter(parameters, versionId, "ranking_weight_recency", "decimal", "0.40");
        parameter(parameters, versionId, "ranking_weight_frequency", "decimal", "0.25");
        parameter(parameters, versionId, "ranking_weight_contract", "decimal", "0.20");
        parameter(parameters, versionId, "ranking_weight_on_time", "decimal", "0.10");
        parameter(parameters, versionId, "ranking_weight_quality", "decimal", "0.05");
        parameter(parameters, versionId, "topup_weight_on_time", "decimal", "0.50");
        parameter(parameters, versionId, "topup_weight_quality", "decimal", "0.50");
        policyParameterRepository.saveAll(parameters);
        return parameters;
    }

    private void parameter(List<PolicyParameter> parameters, Long versionId, String key, String type, String value) {
        PolicyParameter parameter = new PolicyParameter();
        parameter.setVersionId(versionId);
        parameter.setParamKey(key);
        parameter.setValueType(type);
        parameter.setValueJson(value);
        parameter.setScopeJson(null);
        parameters.add(parameter);
    }

    private List<PolicyRule> seedRules(Long versionId) {
        List<PolicyRule> rules = new ArrayList<>();
        rules.add(rule(versionId, "v5.0.1.2", 10, "1.2", "New item sourcing",
                "{\"fact\":\"is_new_item\",\"op\":\"eq\",\"value\":true}",
                outcome("SOURCING_REQUEST", "NEW_ITEM_SOURCING", "none", 0, false)));
        rules.add(rule(versionId, "v5.0.5.1", 20, "5.1", "Capex sourcing",
                "{\"fact\":\"item_type\",\"op\":\"eq\",\"value\":\"Capex\"}",
                outcome("SOURCING_REQUEST", "CAPEX_SOURCING", "none", 5, true)));
        rules.add(rule(versionId, "v5.0.5.2", 30, "5.2", "Contract PO",
                "{\"fact\":\"has_contract_with_ceiling\",\"op\":\"eq\",\"value\":true}",
                outcome("PO", "CONTRACT_PO", "contract_lowest_rate_with_ceiling", 0, false)));
        rules.add(rule(versionId, "v5.0.5.3", 40, "5.3", "Emergency PO",
                "{\"all\":[{\"fact\":\"urgency\",\"op\":\"eq\",\"value\":\"Emergency\"},"
                        + "{\"fact\":\"value_inr\",\"op\":\"lte\",\"param\":\"emergency_limit\"},"
                        + "{\"fact\":\"has_active_history\",\"op\":\"eq\",\"value\":true}]}",
                outcome("PO", "EMERGENCY_PO", "first_preferred_supplier", 0, false)));
        rules.add(rule(versionId, "v5.0.5.4", 50, "5.4", "Spot PO",
                "{\"all\":[{\"fact\":\"value_inr\",\"op\":\"lt\",\"param\":\"spot_limit\"},"
                        + "{\"fact\":\"has_active_history\",\"op\":\"eq\",\"value\":true}]}",
                outcome("PO", "SPOT_PO", "first_preferred_supplier", 0, false)));
        rules.add(rule(versionId, "v5.0.5.5", 60, "5.5", "RFQ",
                "{\"fact\":\"value_inr\",\"op\":\"lt\",\"param\":\"rfq_rfp_boundary\"}",
                outcome("SOURCING_REQUEST", "RFQ", "preferred_top_n", 3, false)));
        rules.add(rule(versionId, "v5.0.5.6", 70, "5.6", "RFP",
                "{\"fact\":\"value_inr\",\"op\":\"gte\",\"param\":\"rfq_rfp_boundary\"}",
                outcome("SOURCING_REQUEST", "RFP", "preferred_top_n", 5, false)));
        policyRuleRepository.saveAll(rules);
        return rules;
    }

    private PolicyRule rule(Long versionId, String key, int priority, String clause, String name,
                            String condition, String outcome) {
        PolicyRule rule = new PolicyRule();
        rule.setVersionId(versionId);
        rule.setRuleKey(key);
        rule.setPriority(priority);
        rule.setClauseRef(clause);
        rule.setName(name);
        rule.setScopeJson(null);
        rule.setConditionJson(condition);
        rule.setOutcomeJson(outcome);
        rule.setEnabled(true);
        return rule;
    }

    private String outcome(String decision, String route, String supplierStrategy, Integer count, boolean capexApproval) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("decision", decision);
        outcome.put("route", route);
        outcome.put("supplier_strategy", supplierStrategy);
        outcome.put("suggestion_count", count);
        outcome.put("requires_capex_approval", capexApproval);
        return PolicyJson.write(outcome);
    }

    private List<CSVRecord> read(String resource) {
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            return parser.getRecords();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read seed " + resource, e);
        }
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }
}
