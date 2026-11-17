package com.corvo.procurement.api;

import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.pr.PrService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/prs")
public class PrController {
    private final PrService prService;

    public PrController(PrService prService) {
        this.prService = prService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return prService.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return prService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return prService.create(body);
    }

    @PostMapping("/{id}/submit")
    public BatchService.BatchResponse submit(@PathVariable Long id) {
        return prService.submit(id);
    }
}
