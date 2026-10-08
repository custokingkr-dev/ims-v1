package com.custoking.ims.platformservice.infrastructure;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Wire-contract parsing must not inherit permissive application JSON settings. */
final class Msg91WireJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Msg91WireJson() {}

    static JsonNode read(String value) {
        return JSON.readTree(value);
    }
}
