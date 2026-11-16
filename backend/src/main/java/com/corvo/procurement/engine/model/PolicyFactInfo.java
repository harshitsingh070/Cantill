package com.corvo.procurement.engine.model;

public record PolicyFactInfo(
        String factKey,
        String valueType,
        String resolverKey,
        String configJson
) {
}
