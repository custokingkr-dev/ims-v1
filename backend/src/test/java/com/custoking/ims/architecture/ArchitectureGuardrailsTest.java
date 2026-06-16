package com.custoking.ims.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureGuardrailsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/custoking/ims");

    @Test
    void controllersDoNotBypassServicesByImportingRepositoriesOrEntities() throws IOException {
        assertNoImports(
                List.of(SOURCE_ROOT.resolve("controller"), SOURCE_ROOT.resolve("auth/controller")),
                List.of("import com.custoking.ims.repo.", "import com.custoking.ims.entity."));
    }

    @Test
    void dtoPackagesStayTransportOnly() throws IOException {
        assertNoImports(
                List.of(SOURCE_ROOT.resolve("dto")),
                List.of(
                        "import com.custoking.ims.repo.",
                        "import com.custoking.ims.entity.",
                        "import com.custoking.ims.service.",
                        "import com.custoking.ims.controller."));
    }

    @Test
    void domainPackagesDoNotDependOnWebOrSecurityLayers() throws IOException {
        assertNoImports(
                List.of(
                        SOURCE_ROOT.resolve("catalog"),
                        SOURCE_ROOT.resolve("fees"),
                        SOURCE_ROOT.resolve("firefighting"),
                        SOURCE_ROOT.resolve("payments"),
                        SOURCE_ROOT.resolve("schools"),
                        SOURCE_ROOT.resolve("students")),
                List.of(
                        "import com.custoking.ims.controller.",
                        "import com.custoking.ims.config.",
                        "import com.custoking.ims.security."));
    }

    private void assertNoImports(List<Path> roots, List<String> disallowedImports) throws IOException {
        List<String> violations = roots.stream()
                .filter(Files::exists)
                .flatMap(root -> javaFiles(root).stream())
                .flatMap(file -> disallowedImports.stream()
                        .filter(pattern -> fileContains(file, pattern))
                        .map(pattern -> file + " imports " + pattern))
                .toList();

        assertThat(violations).isEmpty();
    }

    private List<Path> javaFiles(Path root) {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan " + root, e);
        }
    }

    private boolean fileContains(Path file, String pattern) {
        try {
            return Files.readString(file).contains(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + file, e);
        }
    }
}
