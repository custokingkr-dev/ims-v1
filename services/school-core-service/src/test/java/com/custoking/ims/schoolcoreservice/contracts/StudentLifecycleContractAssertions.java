package com.custoking.ims.schoolcoreservice.contracts;

/** Compatibility facade for existing student contract tests. */
public final class StudentLifecycleContractAssertions {
    private static final OpenApiContractAssertions CONTRACT = new OpenApiContractAssertions("studentClient.v1.openapi.json");
    private StudentLifecycleContractAssertions() {}
    public static void assertResponse(String schema, Object value) { CONTRACT.assertResponse(schema, value); }
    public static void assertSerializedResponse(String schema, String value) { CONTRACT.assertSerializedResponse(schema, value); }
    public static void assertEndpointResponse(String path, String method, String value) { CONTRACT.assertEndpointResponse(path, method, value); }
}
