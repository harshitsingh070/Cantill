package com.corvo.procurement.ingestion;

import com.corvo.procurement.engine.model.PrLineInput;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class CsvPrParser {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIMESTAMP_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<PrLineInput> parse(String csv) {
        List<PrLineInput> lines = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(new StringReader(stripBom(csv)))) {
            for (CSVRecord record : parser) {
                lines.add(new PrLineInput(
                        null,
                        value(record, "pr_no"),
                        integer(record, "pr_line"),
                        timestamp(record, "created_at"),
                        value(record, "requester"),
                        value(record, "department"),
                        value(record, "plant"),
                        value(record, "item_code"),
                        value(record, "description"),
                        decimal(record, "quantity"),
                        value(record, "uom"),
                        decimal(record, "estimated_unit_price"),
                        date(record, "need_by_date"),
                        value(record, "urgency")
                ));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse PR batch CSV", e);
        }
        return lines;
    }

    public PrLineInput withId(PrLineInput input, Long id) {
        return new PrLineInput(
                id,
                input.prNo(),
                input.prLine(),
                input.createdAt(),
                input.requester(),
                input.department(),
                input.plant(),
                input.itemCode(),
                input.description(),
                input.quantity(),
                input.uom(),
                input.estimatedUnitPrice(),
                input.needByDate(),
                input.urgency()
        );
    }

    private String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String value(CSVRecord record, String column) {
        try {
            String value = record.get(column);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer integer(CSVRecord record, String column) {
        String value = value(record, column);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimal(CSVRecord record, String column) {
        String value = value(record, column);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate date(CSVRecord record, String column) {
        String value = value(record, column);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalDateTime timestamp(CSVRecord record, String column) {
        String value = value(record, column);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TIMESTAMP);
        } catch (DateTimeParseException first) {
            try {
                return LocalDateTime.parse(value, TIMESTAMP_SECONDS);
            } catch (DateTimeParseException second) {
                try {
                    return LocalDateTime.parse(value);
                } catch (DateTimeParseException third) {
                    return null;
                }
            }
        }
    }
}
