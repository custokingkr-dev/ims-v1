package com.custoking.ims.billingservice.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: every billing endpoint is currently expected to be superadmin-only. (Billing
 * now has a {@code TenantAwareDataSource} + branch-keyed RLS as a fail-closed backstop, but no
 * school-facing billing endpoint exists yet, so the superadmin gate is still the live enforcement.)
 * This
 * test parses the two billing controllers' SOURCE files and asserts that EVERY request-mapped
 * handler method's body calls {@code TenantScope.requireSuperAdmin()}.
 *
 * <p>The test inspects each handler individually (not "does the string appear somewhere in the
 * file") so that removing the gate from any single handler — while leaving it on every other
 * handler — makes this test fail. See {@link #assertAllHandlersGated(Path)}.
 */
class BillingSuperadminGateArchTest {

    /** Matches @GetMapping / @PostMapping / @PutMapping / @DeleteMapping / @PatchMapping / @RequestMapping. */
    private static final Pattern MAPPING_ANNOTATION =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\b");

    private static final String REQUIRE_SUPERADMIN_CALL = "requireSuperAdmin()";

    @Test
    void everyHandlerInBillingInvoiceControllerIsSuperadminGated() {
        assertAllHandlersGated(controllerSource("api/BillingInvoiceController.java"));
    }

    @Test
    void everyHandlerInBillingPublicCompatibilityControllerIsSuperadminGated() {
        assertAllHandlersGated(controllerSource("api/compat/BillingPublicCompatibilityController.java"));
    }

    /**
     * Belt-and-braces: the two controllers together must expose a non-trivial number of
     * mapping-annotated handlers, so an empty/broken scan (e.g. a typo'd relative path) can
     * never masquerade as "all handlers gated".
     */
    @Test
    void totalHandlerCountAcrossBothControllersIsNonTrivial() {
        int total = extractHandlerBodies(controllerSource("api/BillingInvoiceController.java")).size()
                + extractHandlerBodies(controllerSource("api/compat/BillingPublicCompatibilityController.java")).size();
        assertThat(total).as("total mapping-annotated handlers found across both billing controllers")
                .isGreaterThanOrEqualTo(15);
    }

    private void assertAllHandlersGated(Path sourceFile) {
        List<String> bodies = extractHandlerBodies(sourceFile);
        assertThat(bodies)
                .as("expected at least one mapping-annotated handler in %s", sourceFile)
                .isNotEmpty();

        List<Integer> ungatedHandlerIndices = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            if (!bodies.get(i).contains(REQUIRE_SUPERADMIN_CALL)) {
                ungatedHandlerIndices.add(i);
            }
        }

        assertThat(ungatedHandlerIndices)
                .as("0-indexed handlers (in source order) in %s missing a %s call", sourceFile, REQUIRE_SUPERADMIN_CALL)
                .isEmpty();
    }

    /**
     * Scans the given controller source for every mapping-annotated method and returns each
     * method's full body text (including the enclosing braces). Uses simple paren/brace depth
     * tracking rather than a full Java parser:
     *   1. From the end of the mapping annotation, scan forward tracking paren depth so we skip
     *      over the annotation's own parens, any other annotations (e.g. @ResponseStatus(...))
     *      and the method's parameter list, until we hit a '{' at paren-depth 0 — the method's
     *      opening brace.
     *   2. From that opening brace, track brace depth until it returns to 0 — the method's
     *      closing brace.
     */
    private static List<String> extractHandlerBodies(Path sourceFile) {
        String src;
        try {
            src = Files.readString(sourceFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read controller source: " + sourceFile, e);
        }

        List<String> bodies = new ArrayList<>();
        Matcher matcher = MAPPING_ANNOTATION.matcher(src);
        while (matcher.find()) {
            int i = matcher.end();
            int parenDepth = 0;
            int bodyStart = -1;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '(') {
                    parenDepth++;
                } else if (c == ')') {
                    parenDepth--;
                } else if (c == '{' && parenDepth == 0) {
                    bodyStart = i;
                    break;
                }
                i++;
            }
            if (bodyStart == -1) {
                continue; // malformed / unexpected shape — do not silently fabricate a body
            }

            int braceDepth = 0;
            int j = bodyStart;
            int bodyEnd = -1;
            while (j < src.length()) {
                char c = src.charAt(j);
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0) {
                        bodyEnd = j;
                        break;
                    }
                }
                j++;
            }
            if (bodyEnd == -1) {
                continue;
            }

            bodies.add(src.substring(bodyStart, bodyEnd + 1));
        }
        return bodies;
    }

    /**
     * Resolves a controller source file relative to this module's {@code src/main/java}. Maven
     * Surefire runs with the module directory (e.g. {@code services/billing-service}) as the
     * working directory, so this is a plain relative path — no reflection/classpath games needed.
     */
    private static Path controllerSource(String relativeToApiPackage) {
        return Path.of("src", "main", "java", "com", "custoking", "ims", "billingservice")
                .resolve(relativeToApiPackage);
    }
}
