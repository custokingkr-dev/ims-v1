package com.custoking.ims.schoolcoreservice.api.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.util.AccessPattern;

/**
 * A text field of a partial update that can be cleared: an explicit JSON {@code null} reads as blank (which the repository
 * stores as null, clearing the value), while an omitted field stays null (which keeps the stored value). A plain
 * {@code String} cannot tell the two apart, and Jackson hands an {@code Optional} record component an empty Optional in
 * both cases. Text is read exactly as {@link StringDeserializer} reads it.
 */
public final class ClearableTextDeserializer extends StringDeserializer {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        return super.deserialize(parser, context);
    }

    /** An explicit null: blank, which clears the stored value. */
    @Override
    public Object getNullValue(DeserializationContext context) {
        return "";
    }

    @Override
    public AccessPattern getNullAccessPattern() {
        return AccessPattern.CONSTANT;
    }

    /** An omitted field: null, which keeps the stored value. */
    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
