package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplate;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplateLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The engine's test templates, under {@code src/test/resources/orderworkflow-fixtures}: a 12-step notebook-shaped template
 * and a 9-step flier-shaped one. They are loaded through the real loader, so every engine test also proves they are valid.
 */
public final class TemplateFixtures {
    public static final WorkflowTemplate NOTEBOOK = load("notebook/NOTEBOOK_ORDER_TRACKER.json");
    public static final WorkflowTemplate NINE_STEP = load("nine-step/FLIER_ORDER_WORKFLOW.json");

    private TemplateFixtures() { }

    public static WorkflowTemplate load(String path) {
        try (InputStream input = TemplateFixtures.class.getResourceAsStream("/orderworkflow-fixtures/" + path)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + path);
            return WorkflowTemplateLoader.parse(input, path.substring(path.lastIndexOf('/') + 1));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    public static String text(String path) {
        try (InputStream input = TemplateFixtures.class.getResourceAsStream("/orderworkflow-fixtures/" + path)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + path);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** A stage of the notebook fixture by number. */
    public static StepSpec nb(int order) {
        return NOTEBOOK.step(order).orElseThrow();
    }

    /** A stage of the nine-step fixture by number. */
    public static StepSpec nine(int order) {
        return NINE_STEP.step(order).orElseThrow();
    }
}
