package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.List;

/**
 * A workflow template file is invalid, or does not match its definition's {@code workflow_steps} rows. Thrown at startup,
 * so the service refuses to start (design D2); the message lists every problem found, each prefixed with the file and the
 * JSON path it concerns.
 */
public class InvalidTemplateException extends IllegalStateException {
    private final List<String> problems;

    public InvalidTemplateException(List<String> problems) {
        super(message(problems));
        this.problems = List.copyOf(problems);
    }

    public InvalidTemplateException(List<String> problems, Throwable cause) {
        super(message(problems), cause);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }

    private static String message(List<String> problems) {
        return "Invalid order workflow templates (" + problems.size() + " problem" + (problems.size() == 1 ? "" : "s") + "):\n  - "
                + String.join("\n  - ", problems);
    }
}
