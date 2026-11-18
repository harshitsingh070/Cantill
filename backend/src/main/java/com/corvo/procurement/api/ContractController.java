package com.corvo.procurement.api;

import com.corvo.procurement.contract.ContractService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {
    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return contractService.list();
    }

    @GetMapping("/{contractId}")
    public Map<String, Object> get(@PathVariable String contractId) {
        return contractService.get(contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        return contractService.save(body);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            return contractService.extract(file.getBytes(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read uploaded contract", e);
        }
    }

    @PostMapping(value = "/{contractId}/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> extract(@PathVariable String contractId,
                                       @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> extraction = contractService.extract(file.getBytes(), file.getOriginalFilename());
            extraction.put("contract_id", contractId);
            return extraction;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read uploaded contract", e);
        }
    }
}
