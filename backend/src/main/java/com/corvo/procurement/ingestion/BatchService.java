package com.corvo.procurement.ingestion;

import com.corvo.procurement.domain.BatchRun;
import com.corvo.procurement.domain.BatchRunRepository;
import com.corvo.procurement.domain.ContractConsumptionRun;
import com.corvo.procurement.domain.ContractConsumptionRunRepository;
import com.corvo.procurement.domain.Decision;
import com.corvo.procurement.domain.DecisionLine;
import com.corvo.procurement.domain.DecisionLineRepository;
import com.corvo.procurement.domain.DecisionRepository;
import com.corvo.procurement.domain.PrBatch;
import com.corvo.procurement.domain.PrBatchRepository;
import com.corvo.procurement.domain.PrLine;
import com.corvo.procurement.domain.PrLineRepository;
import com.corvo.procurement.domain.Requirement;
import com.corvo.procurement.domain.RequirementLine;
import com.corvo.procurement.domain.RequirementLineRepository;
import com.corvo.procurement.domain.RequirementRepository;
import com.corvo.procurement.domain.SourcingEvent;
import com.corvo.procurement.domain.SourcingEventRepository;
import com.corvo.procurement.engine.DecisionEngine;
import com.corvo.procurement.engine.EngineDataFactory;
import com.corvo.procurement.engine.model.DecisionRecord;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.EngineResult;
import com.corvo.procurement.engine.model.LineResult;
import com.corvo.procurement.engine.model.PoGroup;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.engine.model.RequirementRecord;
import com.corvo.procurement.policy.PolicyHasher;
import com.corvo.procurement.policy.PolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BatchService {
    private final CsvPrParser parser;
    private final EngineDataFactory engineDataFactory;
    private final PolicyService policyService;
    private final BatchRunRepository batchRunRepository;
    private final PrBatchRepository prBatchRepository;
    private final PrLineRepository prLineRepository;
    private final RequirementRepository requirementRepository;
    private final RequirementLineRepository requirementLineRepository;
    private final DecisionRepository decisionRepository;
    private final DecisionLineRepository decisionLineRepository;
    private final SourcingEventRepository sourcingEventRepository;
    private final ContractConsumptionRunRepository contractConsumptionRunRepository;
    private final ObjectMapper objectMapper;

    public BatchService(CsvPrParser parser,
                        EngineDataFactory engineDataFactory,
                        PolicyService policyService,
                        BatchRunRepository batchRunRepository,
                        PrBatchRepository prBatchRepository,
                        PrLineRepository prLineRepository,
                        RequirementRepository requirementRepository,
                        RequirementLineRepository requirementLineRepository,
                        DecisionRepository decisionRepository,
                        DecisionLineRepository decisionLineRepository,
                        SourcingEventRepository sourcingEventRepository,
                        ContractConsumptionRunRepository contractConsumptionRunRepository,
                        ObjectMapper objectMapper) {
        this.parser = parser;
        this.engineDataFactory = engineDataFactory;
        this.policyService = policyService;
        this.batchRunRepository = batchRunRepository;
        this.prBatchRepository = prBatchRepository;
        this.prLineRepository = prLineRepository;
        this.requirementRepository = requirementRepository;
        this.requirementLineRepository = requirementLineRepository;
        this.decisionRepository = decisionRepository;
        this.decisionLineRepository = decisionLineRepository;
        this.sourcingEventRepository = sourcingEventRepository;
        this.contractConsumptionRunRepository = contractConsumptionRunRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BatchResponse process(byte[] csvBytes, String filename, String uploadedBy) {
        String csv = new String(csvBytes, StandardCharsets.UTF_8);
        return processParsed(parser.parse(csv), PolicyHasher.sha256(csv), filename, uploadedBy);
    }

    @Transactional
    public BatchResponse processInputs(List<PrLineInput> inputs, String filename, String uploadedBy) {
        try {
            return processParsed(inputs, PolicyHasher.sha256(objectMapper.writeValueAsString(inputs)), filename, uploadedBy);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash PR input", e);
        }
    }

    private BatchResponse processParsed(List<PrLineInput> parsed, String inputHash, String filename, String uploadedBy) {
        String runKey = inputHash + ":" + policyService.latestActivatedId();
        Optional<BatchRun> existing = batchRunRepository.findByRunKey(runKey);
        if (existing.isPresent()) {
            BatchRun run = existing.get();
            return new BatchResponse(run.getId(), run.getPolicyVersionLabel(), readResult(run.getResultJson()));
        }

        BatchRun run = new BatchRun();
        run.setRunKey(runKey);
        run.setFilename(filename);
        run.setInputHash(inputHash);
        run.setPolicyVersionLabel(policyService.latestActivatedLabel());
        run.setUploadedBy(uploadedBy);
        run.setCreatedAt(LocalDateTime.now());
        run = batchRunRepository.saveAndFlush(run);

        PrBatch batch = new PrBatch();
        batch.setRunId(run.getId());
        batch.setFilename(filename);
        batch.setUploadedBy(uploadedBy);
        batch.setUploadedAt(LocalDateTime.now());
        batch.setInputHash(inputHash);
        batch = prBatchRepository.saveAndFlush(batch);

        List<PrLineInput> inputs = persistPrLines(batch.getId(), parsed);
        EngineData engineData = engineDataFactory.build();
        EngineResult result = new DecisionEngine(engineData).run(inputs);
        persistEngineResult(run.getId(), result);
        run.setResultJson(writeResult(result));
        batchRunRepository.save(run);
        return new BatchResponse(run.getId(), run.getPolicyVersionLabel(), result);
    }

    public BatchResponse processSample(String uploadedBy) {
        try {
            byte[] bytes = new ClassPathResource("seed/sample_pr_batch.csv").getInputStream().readAllBytes();
            return process(bytes, "sample_pr_batch.csv", uploadedBy);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read sample PR batch", e);
        }
    }

    public EngineResult result(Long runId) {
        BatchRun run = batchRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        return readResult(run.getResultJson());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs() {
        return batchRunRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(run -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", run.getId());
                    body.put("filename", run.getFilename());
                    body.put("policy_version_label", run.getPolicyVersionLabel());
                    body.put("uploaded_by", run.getUploadedBy());
                    body.put("created_at", run.getCreatedAt());
                    return body;
                })
                .toList();
    }

    private List<PrLineInput> persistPrLines(Long batchId, List<PrLineInput> parsed) {
        List<PrLine> entities = new ArrayList<>();
        for (PrLineInput input : parsed) {
            PrLine entity = new PrLine();
            entity.setBatchId(batchId);
            entity.setPrNo(input.prNo() == null ? "UNKNOWN" : input.prNo());
            entity.setPrLine(input.prLine() == null ? 0 : input.prLine());
            entity.setCreatedAt(input.createdAt() == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : input.createdAt());
            entity.setRequester(input.requester());
            entity.setDepartment(input.department());
            entity.setPlant(input.plant());
            entity.setRawItemCode(input.itemCode());
            entity.setRawDescription(input.description());
            entity.setQuantity(input.quantity());
            entity.setRawUom(input.uom());
            entity.setEstimatedUnitPrice(input.estimatedUnitPrice());
            entity.setNeedByDate(input.needByDate());
            entity.setUrgency(input.urgency());
            entities.add(entity);
        }
        entities = prLineRepository.saveAll(entities);
        List<PrLineInput> withIds = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            withIds.add(parser.withId(parsed.get(i), entities.get(i).getId()));
        }
        return withIds;
    }

    private void persistEngineResult(Long runId, EngineResult result) {
        Map<Long, String> poByRequirement = new HashMap<>();
        for (PoGroup group : result.poGroups()) {
            for (Long requirementId : group.requirementIds()) {
                poByRequirement.put(requirementId, group.poId());
            }
        }
        Map<Long, Long> requirementIdMap = new HashMap<>();
        for (RequirementRecord record : result.requirements()) {
            Requirement requirement = new Requirement();
            requirement.setRunId(runId);
            requirement.setEngineRequirementId(record.id());
            requirement.setRequirementDate(record.requirementDate());
            requirement.setFirstCreatedAt(record.firstCreatedAt());
            requirement.setItemCode(record.itemCode());
            requirement.setPlant(record.plant());
            requirement.setUrgency(record.urgency());
            requirement.setContractId(record.usedContractId());
            requirement.setUnitPrice(record.unitPrice());
            requirement.setValue(record.value());
            requirement.setValuationSource(record.valuationSource());
            requirement.setIsNewItem(record.newItem());
            requirement = requirementRepository.saveAndFlush(requirement);
            requirementIdMap.put(record.id(), requirement.getId());
            if (record.usedContractId() != null && record.value() != null) {
                ContractConsumptionRun consumption = new ContractConsumptionRun();
                consumption.setRunId(String.valueOf(runId));
                consumption.setContractId(record.usedContractId());
                consumption.setRequirementId(requirement.getId());
                consumption.setAmount(record.value());
                contractConsumptionRunRepository.save(consumption);
            }
            for (PrLineInput line : record.lines()) {
                RequirementLine requirementLine = new RequirementLine();
                requirementLine.setRequirementId(requirement.getId());
                requirementLine.setPrLineId(line.id());
                requirementLineRepository.save(requirementLine);
            }
        }

        Map<Long, Long> decisionIdMap = new LinkedHashMap<>();
        Map<String, DecisionRecord> decisionByRequirement = new HashMap<>();
        for (DecisionRecord record : result.decisions()) {
            Decision decision = new Decision();
            decision.setRunId(runId);
            decision.setRequirementId(requirementIdMap.getOrDefault(record.requirementId(), record.requirementId()));
            decision.setDecision(record.decision());
            decision.setRoute(record.route());
            decision.setSupplierId(record.supplierId());
            decision.setUnitPrice(record.unitPrice());
            decision.setGroupValueInr(record.groupValueInr());
            decision.setSuggestedSupplierIds(record.suggestedSupplierIds().isEmpty()
                    ? "" : String.join(";", record.suggestedSupplierIds()));
            decision.setPolicyVersion(record.policyVersion());
            decision.setClause(record.clause());
            decision.setSourceReference(record.sourceReference());
            decision.setReason(record.reason());
            decision.setTraceJson(record.traceJson());
            decision.setApprovalStatus("NOT_PROCESSED".equals(record.route()) ? "NOT_APPLICABLE" : "PROPOSED");
            decision.setPoId(poByRequirement.get(record.requirementId()));
            decision.setRequiresCapexApproval(record.requiresCapexApproval());
            decision = decisionRepository.saveAndFlush(decision);
            decisionIdMap.put(record.requirementId(), decision.getId());
            decisionByRequirement.put(String.valueOf(record.requirementId()), record);
            if (!"PO".equals(record.decision()) && !"NOT_PROCESSED".equals(record.route())) {
                SourcingEvent event = new SourcingEvent();
                event.setRoute(record.route());
                event.setRequirementId(requirementIdMap.getOrDefault(record.requirementId(), record.requirementId()));
                event.setSuggestedSupplierIds(String.join(";", record.suggestedSupplierIds()));
                event.setApprovalStatus("PENDING");
                sourcingEventRepository.save(event);
            }
        }

        Map<Long, DecisionRecord> byEngineRequirement = new HashMap<>();
        for (DecisionRecord record : result.decisions()) {
            byEngineRequirement.put(record.requirementId(), record);
        }
        for (LineResult line : result.lines()) {
            if (line.groupId() == null) {
                continue;
            }
            DecisionRecord decision = byEngineRequirement.get(line.groupId());
            if (decision == null) {
                continue;
            }
            Long decisionId = decisionIdMap.get(decision.requirementId());
            if (decisionId != null && line.prLineId() != null) {
                DecisionLine decisionLine = new DecisionLine();
                decisionLine.setDecisionId(decisionId);
                decisionLine.setPrLineId(line.prLineId());
                decisionLineRepository.save(decisionLine);
            }
        }
    }

    private String writeResult(EngineResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize batch result", e);
        }
    }

    private EngineResult readResult(String json) {
        try {
            return objectMapper.readValue(json, EngineResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read batch result", e);
        }
    }

    public record BatchResponse(Long runId, String policyVersionLabel, EngineResult result) {
    }
}
