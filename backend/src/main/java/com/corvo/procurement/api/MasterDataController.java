package com.corvo.procurement.api;

import com.corvo.procurement.domain.Item;
import com.corvo.procurement.domain.ItemRepository;
import com.corvo.procurement.domain.Supplier;
import com.corvo.procurement.domain.SupplierRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MasterDataController {
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;

    public MasterDataController(SupplierRepository supplierRepository, ItemRepository itemRepository) {
        this.supplierRepository = supplierRepository;
        this.itemRepository = itemRepository;
    }

    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers() {
        return supplierRepository.findAll().stream()
                .sorted(Comparator.comparing(Supplier::getSupplierId))
                .map(this::presentSupplier)
                .toList();
    }

    @GetMapping("/items")
    public List<Map<String, Object>> items() {
        return itemRepository.findAll().stream()
                .sorted(Comparator.comparing(Item::getItemCode))
                .map(this::presentItem)
                .toList();
    }

    private Map<String, Object> presentSupplier(Supplier supplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplier.getSupplierId());
        body.put("supplierId", supplier.getSupplierId());
        body.put("name", supplier.getName());
        body.put("city", supplier.getCity());
        body.put("categories", supplier.getCategories());
        body.put("status", supplier.getStatus());
        body.put("on_time_pct", supplier.getOnTimePct());
        body.put("quality_rating", supplier.getQualityRating());
        body.put("active", supplier.isActive());
        return body;
    }

    private Map<String, Object> presentItem(Item item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("item_code", item.getItemCode());
        body.put("itemCode", item.getItemCode());
        body.put("description", item.getDescription());
        body.put("uom", item.getUom());
        body.put("category", item.getCategory());
        body.put("item_type", item.getItemType());
        body.put("standard_price", item.getStandardPrice());
        return body;
    }
}
