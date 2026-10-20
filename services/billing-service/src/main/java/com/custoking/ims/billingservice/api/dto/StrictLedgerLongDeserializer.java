package com.custoking.ims.billingservice.api.dto;
import tools.jackson.core.*;
import tools.jackson.databind.*;
/** Prevent Jackson's permissive float-to-integer coercion in financial commands. */
public class StrictLedgerLongDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long.class, "Ledger amount and identifiers must be integer JSON numbers");
        return parser.getLongValue();
    }
}
