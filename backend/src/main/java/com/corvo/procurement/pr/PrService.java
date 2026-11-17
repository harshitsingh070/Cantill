package com.corvo.procurement.pr;

import com.corvo.procurement.domain.PrBatch;
import com.corvo.procurement.domain.PrBatchRepository;
import com.corvo.procurement.domain.PrLine;
import com.corvo.procurement.domain.PrLineRepository;
import com.corvo.procurement.engine.model.PrLineInput;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.policy.PolicyHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PrService {
    private final PrBatchRepository batchRepository;
    private final PrLineRepository lineRepository;
    private final BatchService batchService;
    private final ObjectMapper objectMapper;

    public PrService(PrBatchRepository batchRepository, PrLineRepository lineRepository,
                     BatchService batchService, ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.batchService = batchService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> body) {
        List<Map<String, Object>> lines = body.get("lines") instanceof List<?> list
                ? toMaps((List<Object>) list)
                : List.of(Map.of());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("At least one PR line is required");
        }
        String requester = string(body.get("requester"));
        String department = string(body.get("department"));
        LocalDateTime now = LocalDateTime.now();
        String prNo = body.get("pr_no") == null
                ? "PR-API-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                : string(body.get("pr_no"));

        PrBatch batch = new PrBatch();
        batch.setFilename("api-pr");
        batch.setUploadedBy(requester == null ? "requester" : requester);
        batch.setUploadedAt(now);
        batch.setInputHash(PolicyHasher.sha256(write(body)));
        batch = batchRepository.saveAndFlush(batch);

        List<PrLine> saved = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> line : lines) {
            PrLine entity = new PrLine();
            entity.setBatchId(batch.getId());
            entity.setPrNo(prNo);
            entity.setPrLine(line.get("pr_line") == null ? index : integer(line.get("pr_line")));
            entity.setCreatedAt(now);
            entity.setRequester(string(firstNonNull(line.get("requester"), requester)));
            entity.setDepartment(string(firstNonNull(line.get("department"), department)));
            entity.setPlant(string(line.get("plant")));
            entity.setRawItemCode(string(firstNonNull(line.get("item_code"), line.get("itemCode"))));
            entity.setRawDescription(string(line.get("description")));
            entity.setQuantity(decimal(line.get("quantity")));
            entity.setRawUom(string(line.get("uom")));
            entity.setEstimatedUnitPrice(decimal(firstNonNull(line.get("estimated_unit_price"), line.get("estimatedUnitPrice"))));
            entity.setNeedByDate(date(firstNonNull(line.get("need_by_date"), line.get("needByDate"))));
            entity.setUrgency(string(line.get("urgency")));
            saved.add(entity);
            index++;
        }
        lineRepository.saveAll(saved);
        return present(batch);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return batchRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(this::present)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        PrBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PR not found: " + id));
        return present(batch);
    }

    @Transactional
    public BatchService.BatchResponse submit(Long id) {
        PrBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PR not found: " + id));
        List<PrLineInput> inputs = lineRepository.findByBatchIdOrderByPrLineAsc(id).stream()
                .map(this::toInput)
                .toList();
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("PR has no lines");
        }
        BatchService.BatchResponse response = batchService.processInputs(inputs,
                "pr-" + id, batch.getUploadedBy() == null ? "requester" : batch.getUploadedBy());
        batch.setRunId(response.runId());
        batchRepository.save(batch);
        return response;
    }

    private Map<String, Object> present(PrBatch batch) {
        List<PrLine> lines = lineRepository.findByBatchIdOrderByPrLineAsc(batch.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", batch.getId());
        body.put("run_id", batch.getRunId());
        body.put("filename", batch.getFilename());
        body.put("uploaded_by", batch.getUploadedBy());
        body.put("uploaded_at", batch.getUploadedAt());
        body.put("submitted", batch.getRunId() != null);
        body.put("lines", lines);
        return body;
    }

    private PrLineInput toInput(PrLine line) {
        return new PrLineInput(line.getId(), line.getPrNo(), line.getPrLine(), line.getCreatedAt(),
                line.getRequester(), line.getDepartment(), line.getPlant(), line.getRawItemCode(),
                line.getRawDescription(), line.getQuantity(), line.getRawUom(),
                line.getEstimatedUnitPrice(), line.getNeedByDate(), line.getUrgency());
    }

    private List<Map<String, Object>> toMaps(List<Object> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> line = new LinkedHashMap<>();
                map.forEach((k, v) -> line.put(String.valueOf(k), v));
                result.add(line);
            }
        }
        return result;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize PR", e);
        }
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

    private Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
