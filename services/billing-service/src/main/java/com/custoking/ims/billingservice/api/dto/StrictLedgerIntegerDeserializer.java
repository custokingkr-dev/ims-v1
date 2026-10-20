package com.custoking.ims.billingservice.api.dto;
import tools.jackson.core.*;
import tools.jackson.databind.*;
public class StrictLedgerIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Integer.class, "Quantity must be an integer JSON number");
        return parser.getIntValue();
    }
}
