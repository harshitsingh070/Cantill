package com.corvo.procurement.ingestion;

import com.corvo.procurement.engine.model.LineResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CsvExporter {
    public static final String HEADER =
            "pr_no,pr_line,resolved_item_code,group_id,po_id,decision,route,supplier_id,unit_price,"
                    + "group_value_inr,suggested_supplier_ids,policy_version,reason";

    public String export(List<LineResult> lines) {
        StringBuilder builder = new StringBuilder(HEADER).append('\n');
        for (LineResult line : lines) {
            String[] values = new String[]{
                    nullSafe(line.prNo()),
                    line.prLine() == null ? "" : String.valueOf(line.prLine()),
                    nullSafe(line.resolvedItemCode()),
                    line.groupId() == null ? "" : String.valueOf(line.groupId()),
                    nullSafe(line.poId()),
                    nullSafe(line.decision()),
                    nullSafe(line.route()),
                    nullSafe(line.supplierId()),
                    money(line.unitPrice()),
                    money(line.groupValueInr()),
                    nullSafe(line.suggestedSupplierIds()),
                    nullSafe(line.policyVersion()),
                    nullSafe(line.reason())
            };
            builder.append(join(values)).append('\n');
        }
        return builder.toString();
    }

    private String join(String[] values) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(escape(values[i]));
        }
        return row.toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains(";") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
