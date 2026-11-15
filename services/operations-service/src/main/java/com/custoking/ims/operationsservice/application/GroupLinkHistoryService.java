package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository.ChangeRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Who changed which WhatsApp group link, when, from where, and what it held before (design D34, Workflow set-up → Groups).
 * Every save path logs its changes ({@link GroupLinkChangeRepository}); this reads the log back, newest first, a page at a
 * time. Superadmin only: the log covers every school, the global internal approval group and the vendors.
 */
@Service
public class GroupLinkHistoryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    static final String SUPERADMIN_ONLY_MESSAGE = "Only superadmin can see who changed WhatsApp group links";

    private final GroupLinkChangeRepository repo;
    private final StaffNames names;
    private final TransactionTemplate tx;

    public GroupLinkHistoryService(GroupLinkChangeRepository repo, StaffNames names, TransactionTemplate tx) {
        this.repo = repo;
        this.names = names;
        this.tx = tx;
    }

    /**
     * One logged change.
     *
     * @param kind          {@code SCHOOL_GROUP}, {@code INTERNAL_APPROVAL} (a school's own), {@code ITEM} (a school's
     *                      group for one item), {@code GLOBAL_INTERNAL_APPROVAL} or {@code VENDOR}
     * @param schoolId      for a school's groups and item groups
     * @param categoryCode  for an item group, such as {@code FLIERS}
     * @param vendorId      for a vendor's group
     * @param vendorName    the vendor's current name (null if the vendor is gone)
     * @param before        the link before; null when the group was added
     * @param after         the link after; null when it was removed
     * @param outcome       {@code ADDED}, {@code CHANGED} or {@code REMOVED}
     * @param source        {@code SETUP}, {@code ORDER} (an order's School Group Chat card), {@code IMPORT} (the
     *                      spreadsheet import) or {@code VENDOR_DIRECTORY}
     * @param orderId       the order's number, for {@code ORDER}
     * @param changedBy     the user id of who changed it
     * @param changedByName their name from identity; the email they were signed in with when identity does not answer;
     *                      null when neither is known
     */
    public record GroupLinkChangeView(long id, OffsetDateTime changedAt, String kind, Long schoolId, String categoryCode,
                                      Long vendorId, String vendorName, String before, String after, String outcome,
                                      String source, String orderId, long changedBy, String changedByName) { }

    /**
     * A page of the log.
     *
     * @param nextBefore pass as {@code before} for the next (older) page; null when there is none
     */
    public record GroupLinkChangePage(List<GroupLinkChangeView> changes, Long nextBefore) { }

    /**
     * The logged changes, newest first.
     *
     * @param schoolId only that school's own groups and item groups when given
     * @param before   only changes older than this id (the previous page's {@code nextBefore}) when given
     * @param limit    at most this many, 1 to {@link #MAX_LIMIT}; {@link #DEFAULT_LIMIT} when absent
     * @throws WorkflowRuleViolation {@code SUPERADMIN_ONLY} (403)
     */
    public GroupLinkChangePage changes(Long schoolId, Long before, Integer limit) {
        if (OrderWorkflowAccess.role(false, GroupLinkService.STAFF_ONLY) != Role.SUPERADMIN) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, SUPERADMIN_ONLY_MESSAGE);
        }
        if (schoolId != null && schoolId <= 0) throw bad("A positive schoolId is required");
        if (before != null && before <= 0) throw bad("before must be a positive change id");
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw bad("limit must be between 1 and " + MAX_LIMIT);
        // One more than asked tells whether an older page exists.
        List<ChangeRow> rows = tx.execute(status -> repo.changes(schoolId, before, size + 1));
        boolean more = rows.size() > size;
        List<ChangeRow> page = more ? rows.subList(0, size) : rows;
        // Identity is asked outside the transaction, which StaffNames requires.
        Map<Long, String> known = names.lookup(page.stream().map(ChangeRow::changedBy).toList());
        List<GroupLinkChangeView> views = page.stream().map(row -> view(row, known.get(row.changedBy()))).toList();
        return new GroupLinkChangePage(views, more ? page.get(page.size() - 1).id() : null);
    }

    private static GroupLinkChangeView view(ChangeRow row, String name) {
        String outcome = row.before() == null ? "ADDED" : row.after() == null ? "REMOVED" : "CHANGED";
        return new GroupLinkChangeView(row.id(), row.changedAt(), row.kind().name(), row.schoolId(), row.categoryCode(),
                row.vendorId(), row.vendorName(), row.before(), row.after(), outcome, row.source().name(), row.orderId(),
                row.changedBy(), name != null ? name : row.changedByEmail());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
