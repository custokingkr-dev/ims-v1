package com.custoking.ims.identityservice.api.internal;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.StaffName;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The operator directory for peer services: which operator a new notebook order goes to (school-core at placement),
 * whether a user may be assigned tracker work, and the names of the staff the tracker shows (both operations-service).
 * Cloud Run IAM and the exact signed MachineCallerFilter authorize each route's caller
 * (OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS for resolve; OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS
 * for eligible and names); no shared token or user context.
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

    /** At most {@link OperatorDirectory#MAX_NAME_IDS} user ids. */
    public record NamesRequest(List<Long> userIds) { }

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

    /**
     * Full names of the staff among these users, for the tracker's assignees and note authors: only active users holding
     * an OPERATIONS or SUPERADMIN assignment. Any other id is left out of the answer, never refused one by one.
     */
    @PostMapping("/names")
    public List<StaffName> names(@RequestBody NamesRequest request) {
        try {
            return directory.names(request == null ? null : request.userIds());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }
}
