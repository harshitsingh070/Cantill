package com.corvo.procurement.engine.model;

public record PolicyParameterInfo(
        Long id,
        String key,
        String valueType,
        Object value,
        String scopeJson
) {
}
