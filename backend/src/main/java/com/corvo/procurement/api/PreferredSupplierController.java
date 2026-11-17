package com.corvo.procurement.api;

import com.corvo.procurement.engine.model.PreferredSupplier;
import com.corvo.procurement.ranking.PreferredSupplierService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/preferred-suppliers")
public class PreferredSupplierController {
    private final PreferredSupplierService service;

    public PreferredSupplierController(PreferredSupplierService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, List<PreferredSupplier>> list(
            @RequestParam("as_of_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(value = "item_code", required = false) String itemCode) {
        return service.list(asOfDate, itemCode);
    }

    @GetMapping("/export.csv")
    public ResponseEntity<String> export(
            @RequestParam("as_of_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(value = "item_code", required = false) String itemCode) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=preferred-suppliers.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.exportTopThree(asOfDate, itemCode));
    }
}
