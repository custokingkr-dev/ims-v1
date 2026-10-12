package com.custoking.ims.schoolcoreservice.contracts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** Bounded test-only checker for the schema features used by the browser contracts. */
public final class OpenApiContractAssertions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode CONTRACT;
    public OpenApiContractAssertions(String filename) { CONTRACT = load(filename); }

    private static JsonNode load(String filename) {
        try {
            Path directory = Path.of("").toAbsolutePath();
            while (directory != null) {
                Path file = directory.resolve("contracts/openapi").resolve(filename);
                if (Files.exists(file)) return JSON.readTree(Files.readString(file));
                directory = directory.getParent();
            }
            throw new IllegalStateException("Browser contract not found");
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    public void assertResponse(String schema, Object value) {
        JsonNode serialized = JSON.readTree(JSON.writeValueAsString(value));
        check(serialized, CONTRACT.path("components").path("schemas").path(schema), schema);
    }

    public void assertSerializedResponse(String schema, String value) {
        check(JSON.readTree(value), CONTRACT.path("components").path("schemas").path(schema), schema);
    }

    public void assertEndpointResponse(String templatePath, String method, String value) {
        JsonNode schema = CONTRACT.path("paths").path(templatePath).path(method).path("responses")
                .path("200").path("content").path("application/json").path("schema");
        check(JSON.readTree(value), schema, method + " " + templatePath);
    }

    private void check(JsonNode value, JsonNode schema, String location) {
        assertThat(schema.isMissingNode()).as("schema exists: %s", location).isFalse();
        if (schema.has("$ref")) {
            check(value, CONTRACT.at(schema.path("$ref").asString().substring(1)), location);
            return;
        }
        if (schema.has("anyOf")) {
            for (JsonNode variant : schema.path("anyOf")) {
                try { check(value, variant, location); return; } catch (AssertionError ignored) {}
            }
            throw new AssertionError(location + " matches no response variant: " + value);
        }
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode variant : type) {
                if (variant.asString().equals("null") && value.isNull()) return;
            }
        }
        String concrete = type.isArray() ? type.get(0).asString() : type.asString();
        boolean valid = switch (concrete) {
            case "null" -> value.isNull();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> throw new AssertionError("Unsupported contract schema at " + location);
        };
        assertThat(valid).as("%s has type %s: %s", location, type, value).isTrue();
        if (schema.has("enum")) {
            boolean member = false;
            for (JsonNode candidate : schema.path("enum")) member |= candidate.equals(value);
            assertThat(member).as("%s is an allowed value", location).isTrue();
        }
        if (value.isArray()) {
            int index = 0;
            for (JsonNode item : value) check(item, schema.path("items"), location + "[" + index++ + "]");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                assertThat(value.has(required.asString())).as("%s requires %s", location, required).isTrue();
            }
            for (String name : value.propertyNames()) {
                JsonNode property = schema.path("properties").path(name);
                if (!property.isMissingNode()) check(value.path(name), property, location + "." + name);
                else {
                    JsonNode additional = schema.path("additionalProperties");
                    assertThat(additional.isObject() || additional.asBoolean()).as("%s allows property %s", location, name).isTrue();
                    if (additional.isObject()) check(value.path(name), additional, location + "." + name);
                }
            }
        }
    }
}
