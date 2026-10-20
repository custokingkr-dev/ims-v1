package com.custoking.ims.identityservice.api.internal;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The operator directory for peer services: which operator a new notebook order goes to (school-core at placement),
 * and whether a user may be assigned tracker work (operations-service). Cloud Run IAM and the exact signed
 * MachineCallerFilter authorize each route's caller (OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS and
 * OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS); no shared token or user context.
 */
@RestController
@RequestMapping("/api/v1/internal/operator-directory")
public class OperatorDirectoryController {
    private final OperatorDirectory directory;

    public OperatorDirectoryController(OperatorDirectory directory) {
        this.directory = directory;
    }

    public record Resolved(String outcome, Long userId, List<Long> candidates) { }

    public record Eligibility(boolean eligible) { }

    /** Which operator gets a new order for this school and catalogue item, or why none was picked. */
    @GetMapping("/resolve")
    public Resolved resolve(@RequestParam long schoolId, @RequestParam String catalogCategory) {
        try {
            OperatorSelection.Result result = directory.resolve(schoolId, catalogCategory);
            return new Resolved(result.outcome().name(), result.userId(), result.candidates());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** Whether a user may be assigned work for this school and catalogue item. */
    @GetMapping("/eligible")
    public Eligibility eligible(@RequestParam long userId, @RequestParam long schoolId, @RequestParam String catalogCategory) {
        try {
            return new Eligibility(directory.eligible(userId, schoolId, catalogCategory));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }
}
