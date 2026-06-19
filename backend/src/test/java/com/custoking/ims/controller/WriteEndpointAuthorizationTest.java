package com.custoking.ims.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reflection-based test: every mutating endpoint (POST, PUT, PATCH, DELETE)
 * must have an explicit {@code @PreAuthorize} annotation at the method level
 * OR at the class level.
 *
 * No Spring context, no Docker. Uses Spring's classpath scanner (already on
 * the compile-test classpath) to locate {@code @RestController} classes.
 * Runs on every {@code mvn test}.
 *
 * Rationale: a write endpoint without @PreAuthorize is open to any
 * authenticated user regardless of their permissions. This test catches the
 * omission at commit time rather than in a live environment.
 */
@DisplayName("Every write endpoint must have @PreAuthorize")
class WriteEndpointAuthorizationTest {

    private static final String CONTROLLER_BASE_PACKAGE = "com.custoking.ims";

    /**
     * Controllers with intentionally public write endpoints.
     *
     * {@code AuthController} provides login / logout / refresh endpoints that are
     * explicitly permitted without a prior session — they must not carry
     * {@code @PreAuthorize} because unauthenticated clients need to reach them.
     */
    private static final Set<String> EXCLUDED_CONTROLLERS = Set.of("AuthController");

    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Annotation>> WRITE_ANNOTATIONS = List.of(
            PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class
    );

    @Test
    @DisplayName("All POST/PUT/PATCH/DELETE controller methods carry @PreAuthorize")
    void allWriteEndpoints_havePreAuthorize() throws ClassNotFoundException {
        List<String> violations = new ArrayList<>();

        // Use Spring's scanner — already available in test scope via spring-context
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        var beanDefs = scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE);
        assertThat(beanDefs).as("Expected to find at least one @RestController").isNotEmpty();

        for (var beanDef : beanDefs) {
            Class<?> controller = Class.forName(beanDef.getBeanClassName());

            // Auth endpoints are intentionally public — skip the whole controller
            if (EXCLUDED_CONTROLLERS.contains(controller.getSimpleName())) continue;

            // Class-level @PreAuthorize covers every method in the class
            boolean classHasAuth = controller.isAnnotationPresent(PreAuthorize.class);

            for (Method method : controller.getDeclaredMethods()) {
                boolean isWrite = WRITE_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
                if (!isWrite) continue;

                boolean methodHasAuth = method.isAnnotationPresent(PreAuthorize.class);

                if (!classHasAuth && !methodHasAuth) {
                    violations.add(String.format("  %s.%s() [%s]",
                            controller.getSimpleName(),
                            method.getName(),
                            writeAnnotationLabel(method)));
                }
            }
        }

        assertThat(violations)
                .as("Write endpoints missing @PreAuthorize — add the annotation to fix each:\n%s",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("At least one @RestController is detected in the scan")
    void controllerScan_findsControllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var found = scanner.findCandidateComponents(CONTROLLER_BASE_PACKAGE);
        assertThat(found).hasSizeGreaterThan(5);
    }

    private static String writeAnnotationLabel(Method method) {
        return WRITE_ANNOTATIONS.stream()
                .filter(method::isAnnotationPresent)
                .map(Class::getSimpleName)
                .findFirst()
                .orElse("?");
    }
}
