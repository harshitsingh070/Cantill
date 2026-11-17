package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.ContractInfo;
import com.corvo.procurement.engine.model.ContractLineInfo;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.PoRecord;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.corvo.procurement.engine.model.SupplierInfo;
import com.corvo.procurement.policy.ParameterResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PreferredSupplierEngine {
    private final EngineData data;

    public PreferredSupplierEngine(EngineData data) {
        this.data = data;
    }

    public List<PreferredSupplier> compute(ItemInfo item, LocalDate asOfDate, PolicySnapshot policy, ParameterResolver resolver) {
        if (item == null || asOfDate == null || policy == null) {
            return List.of();
        }
        RequirementFacts facts = catalogueFacts(item);
        int preferredHistoryDays = resolver.resolveInt("preferred_history_days", facts);
        int freshDays = resolver.resolveInt("recency_fresh_days", facts);
        int usableDays = resolver.resolveInt("recency_usable_days", facts);
        BigDecimal weightRecency = resolver.resolveDecimal("ranking_weight_recency", facts);
        BigDecimal weightFrequency = resolver.resolveDecimal("ranking_weight_frequency", facts);
        BigDecimal weightContract = resolver.resolveDecimal("ranking_weight_contract", facts);
        BigDecimal weightOnTime = resolver.resolveDecimal("ranking_weight_on_time", facts);
        BigDecimal weightQuality = resolver.resolveDecimal("ranking_weight_quality", facts);

        LocalDate from = asOfDate.minusDays(preferredHistoryDays);
        List<Candidate> candidates = new ArrayList<>();
        for (SupplierInfo supplier : data.suppliers().values()) {
            if (!supplier.isActive()) {
                continue;
            }
            List<PoRecord> itemPos = data.poHistory().stream()
                    .filter(po -> po.itemCode().equals(item.itemCode()))
                    .filter(po -> po.supplierId().equals(supplier.supplierId()))
                    .filter(po -> !po.poDate().isBefore(from) && !po.poDate().isAfter(asOfDate))
                    .sorted(Comparator.comparing(PoRecord::poDate)
                            .thenComparing(po -> ContractEvaluator.poNumberKey(po.poNumber())))
                    .toList();
            ContractInfo latestContract = latestContractForSupplier(item.itemCode(), supplier.supplierId(), asOfDate);
            boolean hasContract = latestContract != null;
            if (itemPos.isEmpty() && !hasContract) {
                continue;
            }
            int poCount = itemPos.size();
            LocalDate lastPoDate = itemPos.isEmpty() ? null : itemPos.get(itemPos.size() - 1).poDate();
            long lastPoDays = lastPoDate == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(lastPoDate, asOfDate);
            BigDecimal recency = lastPoDays <= freshDays ? BigDecimal.ONE
                    : lastPoDays <= usableDays ? new BigDecimal("0.5") : BigDecimal.ZERO;
            String bucket = lastPoDays <= freshDays ? "fresh" : lastPoDays <= usableDays ? "usable" : "stale";

            BigDecimal referencePrice = null;
            String referenceSource = null;
            if (!itemPos.isEmpty()) {
                PoRecord lastPo = itemPos.get(itemPos.size() - 1);
                referencePrice = lastPo.unitPrice();
                referenceSource = "PO " + lastPo.poNumber() + " dated " + lastPo.poDate();
            } else {
                ContractLineInfo line = latestContract.lineFor(item.itemCode());
                if (line != null) {
                    referencePrice = line.rate();
                    referenceSource = "contract " + latestContract.contractId();
                }
            }
            candidates.add(new Candidate(supplier, poCount, recency, bucket, hasContract, referencePrice, referenceSource));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        int maxPoCount = candidates.stream().mapToInt(Candidate::poCount).max().orElse(1);
        if (maxPoCount <= 0) {
            maxPoCount = 1;
        }
        for (Candidate candidate : candidates) {
            BigDecimal frequency = new BigDecimal(candidate.poCount())
                    .divide(new BigDecimal(maxPoCount), 6, RoundingMode.HALF_UP);
            BigDecimal onTime = safe(candidate.supplier().onTimePct()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
            BigDecimal quality = safe(candidate.supplier().qualityRating()).divide(new BigDecimal("5"), 6, RoundingMode.HALF_UP);
            BigDecimal score = weightRecency.multiply(candidate.recency())
                    .add(weightFrequency.multiply(frequency))
                    .add(weightContract.multiply(candidate.hasContract() ? BigDecimal.ONE : BigDecimal.ZERO))
                    .add(weightOnTime.multiply(onTime))
                    .add(weightQuality.multiply(quality))
                    .setScale(6, RoundingMode.HALF_UP);
            candidate.score = score;
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("recency", candidate.recency());
            components.put("po_count", candidate.poCount());
            components.put("max_po_count", maxPoCount);
            components.put("frequency", frequency);
            components.put("has_contract", candidate.hasContract());
            components.put("on_time_pct", candidate.supplier().onTimePct());
            components.put("quality_rating", candidate.supplier().qualityRating());
            components.put("weights", Map.of(
                    "recency", weightRecency,
                    "frequency", weightFrequency,
                    "contract", weightContract,
                    "on_time", weightOnTime,
                    "quality", weightQuality
            ));
            components.put("reference_source", candidate.referenceSource());
            candidate.components = components;
        }
        candidates.sort(Comparator.comparing((Candidate c) -> c.score).reversed()
                .thenComparing(c -> c.supplier().supplierId()));
        List<PreferredSupplier> result = new ArrayList<>();
        int rank = 1;
        for (Candidate candidate : candidates) {
            result.add(new PreferredSupplier(
                    rank++,
                    candidate.supplier().supplierId(),
                    candidate.score,
                    candidate.components,
                    candidate.referencePrice,
                    candidate.referenceSource,
                    candidate.hasContract(),
                    candidate.poCount(),
                    candidate.recencyBucket()
            ));
        }
        return result;
    }

    public List<String> suggestions(ItemInfo item, LocalDate asOfDate, int count, PolicySnapshot policy, ParameterResolver resolver) {
        if (item == null || count <= 0) {
            return List.of();
        }
        List<PreferredSupplier> preferred = compute(item, asOfDate, policy, resolver);
        List<String> suggestions = new ArrayList<>();
        for (PreferredSupplier supplier : preferred) {
            if (suggestions.size() >= count) {
                break;
            }
            suggestions.add(supplier.supplierId());
        }
        if (suggestions.size() >= count) {
            return suggestions;
        }
        RequirementFacts facts = catalogueFacts(item);
        BigDecimal onTimeWeight = resolver.resolveDecimal("topup_weight_on_time", facts);
        BigDecimal qualityWeight = resolver.resolveDecimal("topup_weight_quality", facts);
        List<SupplierInfo> topup = new ArrayList<>();
        for (SupplierInfo supplier : data.suppliers().values()) {
            if (!supplier.isActive() || suggestions.contains(supplier.supplierId())) {
                continue;
            }
            if (supplierCategories(supplier).stream().noneMatch(c -> c.equalsIgnoreCase(item.category()))) {
                continue;
            }
            topup.add(supplier);
        }
        topup.sort(Comparator
                .comparing((SupplierInfo s) -> topupScore(s, onTimeWeight, qualityWeight)).reversed()
                .thenComparing(SupplierInfo::supplierId));
        for (SupplierInfo supplier : topup) {
            if (suggestions.size() >= count) {
                break;
            }
            suggestions.add(supplier.supplierId());
        }
        return suggestions;
    }

    public BigDecimal topupScore(SupplierInfo supplier, BigDecimal onTimeWeight, BigDecimal qualityWeight) {
        BigDecimal onTime = safe(supplier.onTimePct()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        BigDecimal quality = safe(supplier.qualityRating()).divide(new BigDecimal("5"), 6, RoundingMode.HALF_UP);
        return onTimeWeight.multiply(onTime).add(qualityWeight.multiply(quality)).setScale(6, RoundingMode.HALF_UP);
    }

    private ContractInfo latestContractForSupplier(String itemCode, String supplierId, LocalDate asOfDate) {
        return data.contracts().stream()
                .filter(c -> c.supplierId().equals(supplierId))
                .filter(c -> c.lineFor(itemCode) != null)
                .filter(c -> !c.startDate().isAfter(asOfDate))
                .sorted(Comparator.comparing(ContractInfo::startDate)
                        .thenComparing(ContractInfo::contractId).reversed())
                .findFirst()
                .orElse(null);
    }

    private List<String> supplierCategories(SupplierInfo supplier) {
        return supplier.categories() == null ? List.of() : supplier.categories();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private RequirementFacts catalogueFacts(ItemInfo item) {
        return new RequirementFacts(
                item.itemCode(),
                item.itemType(),
                item.category(),
                null,
                null,
                null,
                null,
                item.uom(),
                null,
                false,
                false,
                false,
                false
        );
    }

    private static final class Candidate {
        private final SupplierInfo supplier;
        private final int poCount;
        private final BigDecimal recency;
        private final String recencyBucket;
        private final boolean hasContract;
        private final BigDecimal referencePrice;
        private final String referenceSource;
        private BigDecimal score;
        private Map<String, Object> components;

        private Candidate(SupplierInfo supplier, int poCount, BigDecimal recency, String recencyBucket,
                          boolean hasContract, BigDecimal referencePrice, String referenceSource) {
            this.supplier = supplier;
            this.poCount = poCount;
            this.recency = recency;
            this.recencyBucket = recencyBucket;
            this.hasContract = hasContract;
            this.referencePrice = referencePrice;
            this.referenceSource = referenceSource;
        }

        SupplierInfo supplier() {
            return supplier;
        }

        int poCount() {
            return poCount;
        }

        BigDecimal recency() {
            return recency;
        }

        String recencyBucket() {
            return recencyBucket;
        }

        boolean hasContract() {
            return hasContract;
        }

        BigDecimal referencePrice() {
            return referencePrice;
        }

        String referenceSource() {
            return referenceSource;
        }
    }
}
