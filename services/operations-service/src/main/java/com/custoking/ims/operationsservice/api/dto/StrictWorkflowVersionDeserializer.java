package com.custoking.ims.operationsservice.api.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Concurrency tokens must never be coerced from strings or rounded fractions. */
public class StrictWorkflowVersionDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long.class, "Workflow expectedVersion must be an integer JSON number");
        return parser.getLongValue();
    }
}
