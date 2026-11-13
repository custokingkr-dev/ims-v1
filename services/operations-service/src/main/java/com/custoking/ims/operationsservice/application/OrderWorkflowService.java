package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.OrderWorkflowAccess.Cleared;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource.OpenCallback;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.DesignDecision;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.EffectiveSla;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.NoteEntry;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderCapabilities;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderContacts;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowCounts;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowListItem;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowPage;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.SignOffRecord;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.StepCapabilities;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.StepView;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.WorkflowProgressView;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.GateFacts;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.GateFailure;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Gates;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.NoteRules;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.StepDataRules;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.StepStatus;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions.UpcomingAction;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowProgress;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.application.orderworkflow.template.BlockSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.BlockType;
import com.custoking.ims.operationsservice.application.orderworkflow.template.ChecklistSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateType;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplate;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplates;
import com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway;
import com.custoking.ims.operationsservice.infrastructure.OperatorEligibility;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.DefinitionStep;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.DesignerRow;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.ListFilter;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.ListRow;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.NewTracker;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.NoteRow;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.Scope;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.StepRow;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.TrackerRow;
import com.custoking.ims.operationsservice.persistence.TrackerStatusOutbox;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Orchestrates every item's order workflow (design D1, §3.5), generalised from the notebook order tracker: the template
 * of the order's item decides the stages, cards, fields, checklists, gates and sign-offs, and the pure rules in
 * {@code application.orderworkflow.rules} decide what is allowed. This class loads and saves the state inside one
 * transaction per change, with the order's tracker row locked so two people cannot move the same order at once.
 *
 * <p>Peers (identity for eligibility and names, the catalogue for design files and the order's status) are asked before
 * that transaction opens or after it commits, never while the lock is held.
 *
 * <p>Who may do what (D42): superadmin everything; an operator only on orders of the schools and items they hold
 * ({@link OrderWorkflowAccess}), and only as the assignee of the stage they change; school administrators nothing.
 */
@Service
public class OrderWorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderWorkflowService.class);

    /** Orders moved from the retired PR #380 production workflow are marked with this source (design D4). */
    public static final String IMPORTED_FROM_V36 = "PRODUCT_WORKFLOW_V36";
    /** The field of CDR Conversion that Head Approval binds to (design D21). */
    static final String CDR_LINK_FIELD = "cdrLink";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    /** The school-level groups the school endpoints keep (design §3.5); vendor groups now live on the vendor records. */
    public static final Set<String> SCHOOL_WHATSAPP_TARGETS = Set.of("SCHOOL_GROUP", "INTERNAL_APPROVAL");
    static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    static final String CDR_LINK_MISSING = "CDR_LINK_MISSING";
    static final String NO_SIGN_OFF = "NO_SIGN_OFF";
    static final String ORDER_ASSIGNED = "ORDER_ASSIGNED";

    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,255}");
    private static final Pattern ITEM_CODE = Pattern.compile("[A-Z]{2,4}");
    private static final Pattern ITEM_LIST = Pattern.compile("[A-Z]{2,4}(,[A-Z]{2,4})*");
    private static final Pattern STEP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    private static final Pattern ORDER_NUMBER = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,39}");
    private static final Pattern PHONE = Pattern.compile("[0-9+()\\-. ]{3,32}");
    private static final Set<String> PHASES = Set.of("INTAKE", "PLANNING", "PRODUCTION", "VERIFICATION", "DELIVERY");
    /** The list's {@code sla} filter: an SLA state of the active step, or DUE_TODAY, the "Due today" chip (§4.2). */
    private static final Set<String> SLA_FILTERS = Set.of("OVERDUE", "DUE_SOON", "ON_TRACK", "NO_TIMER", "DUE_TODAY");
    private static final Set<String> SORTS = Set.of("due", "placed", "number");
    /** Vendors one category of one order may hold. */
    private static final int MAX_VENDORS_PER_CATEGORY = 20;
    private static final String WHATSAPP_STAFF_ONLY = "WhatsApp group links are for superadmin and operators only";
    private static final String STAGE_CHANGED = "This stage changed since you loaded it; reload and try again";

    private final OrderWorkflowRepository repo;
    private final OrderWorkflowAccess access;
    private final WorkflowTemplates templates;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OperatorEligibility eligibility;
    private final CatalogOrderGateway catalog;
    private final TrackerStatusOutbox statuses;
    private final StaffNames names;
    private final VendorLookup vendors;
    private final GroupLinkResolver groups;
    private final CallbackSource callbacks;
    private final ObjectMapper json;

    public OrderWorkflowService(OrderWorkflowRepository repo, OrderWorkflowAccess access, WorkflowTemplates templates,
                                TransactionTemplate tx, Clock clock, OperatorEligibility eligibility, CatalogOrderGateway catalog,
                                TrackerStatusOutbox statuses, StaffNames names, VendorLookup vendors, GroupLinkResolver groups,
                                CallbackSource callbacks, ObjectMapper json) {
        this.repo = repo;
        this.access = access;
        this.templates = templates;
        this.tx = tx;
        this.clock = clock;
        this.eligibility = eligibility;
        this.catalog = catalog;
        this.statuses = statuses;
        this.names = names;
        this.vendors = vendors;
        this.groups = groups;
        this.callbacks = callbacks;
        this.json = json;
    }

    // ==== commands and answers ===========================================================================================

    /**
     * School-core's create call (design §3.5 machine create): today's notebook fields plus the template, item and number,
     * and the retired production workflow's progress for an order moved onto the engine. A school-core that predates the
     * item fields sends none of them; the order is then a notebook order.
     */
    public record CreateWorkflow(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                                 String timeZone, Long placedBy, Long assigneeUserId, String schoolName, String definitionId,
                                 String categoryCode, String itemCode, String itemNoun, String orderNumber,
                                 List<ImportedStep> importedProgress) {
        public CreateWorkflow {
            importedProgress = importedProgress == null ? List.of() : importedProgress.stream().filter(Objects::nonNull).toList();
        }

        /** A notebook order as school-core sent it before the item order workflows. */
        public CreateWorkflow(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                              String timeZone, Long placedBy, Long assigneeUserId, String schoolName) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, schoolName,
                    null, null, null, null, null, List.of());
        }

        public CreateWorkflow(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                              String timeZone, Long placedBy, Long assigneeUserId) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, null);
        }
    }

    /**
     * One stage of a retired PR #380 production workflow (catalog V36, design D4). {@code stepCode} is the engine's code;
     * {@code sourceStageCode} the V36 one. {@code status} is {@code COMPLETED}, or {@code ACTIVE} for the stage in progress
     * whose "Production update" ({@code details}) and {@code note} are kept as stage notes.
     */
    public record ImportedStep(String stepCode, String sourceStageCode, Integer stepOrder, String status, OffsetDateTime completedAt,
                               Long completedBy, String details, String note) { }

    public record Created(long instanceId, boolean created) { }

    /** The list's query (design §3.5 {@code GET /item-orders}); every part is optional. */
    public record ListQuery(String item, String step, String phase, String assignee, Long schoolId, String sla, String q,
                            Boolean includeFinished, String sort, Integer limit, Integer offset) {
        public static ListQuery all() {
            return new ListQuery(null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * One step of a template's time slots for a school (design D23, the Time slots tab): the template default and what
     * this school's orders use (its override, or the default).
     */
    public record TimeSlotRow(int step, String code, String title, Sla defaultSla, EffectiveSla effective) { }

    /** The order's contacts as an assignee or superadmin saves them (design D36); blank clears. */
    public record ContactsChange(String schoolContactName, String schoolContactPhone, String factoryContactName,
                                 String factoryContactPhone) { }

    /** School-core's question before a design-file upload or delete: may this user change them now? */
    public record DesignFileAccess(boolean allowed, String reason) { }

    // ==== creation (machine call from school-core at order placement) ====================================================

    /**
     * Idempotent: a second call for the same order returns the existing workflow, and fills in its order number when the
     * workflow has none yet (design D13). A new workflow's status is queued for the catalogue and pushed once the
     * creation has committed, so the order's list shows its step at once; so is an existing one's when its number is filled
     * (see {@code reportAgain}), since a workflow created before numbers never pushed its step.
     */
    public Created create(CreateWorkflow cmd) {
        if (cmd.orderId() == null || !ORDER_ID.matcher(cmd.orderId()).matches()) {
            throw bad("orderId is required: letters, digits, '-' and '_' (255 characters at most)");
        }
        if (cmd.schoolId() <= 0) throw bad("A positive schoolId is required");
        if (cmd.orderedCount() <= 0) throw bad("orderedCount must be positive");
        if (cmd.placedAt() == null) throw bad("placedAt is required");
        if (cmd.schoolName() != null && cmd.schoolName().strip().length() > 200) throw bad("schoolName is limited to 200 characters");
        ZoneId zone = zone(cmd.timeZone());
        Item item = item(cmd);
        String orderNumber = orderNumber(cmd.orderNumber());
        Created created;
        boolean[] statusQueued = new boolean[1];
        try {
            created = createInTransaction(cmd, item, zone, orderNumber, statusQueued);
        } catch (DuplicateKeyException duplicate) {
            // A concurrent call for the same order committed first and this attempt rolled back: return what it created.
            return existingAfterRace(cmd);
        }
        if (created.created() || statusQueued[0]) statuses.attempt(cmd.orderId());
        return created;
    }

    /** The template, catalogue category, item code and noun a create names; a notebook order when it names none. */
    private record Item(WorkflowTemplate template, String categoryCode, String itemCode, String itemNoun) { }

    private Item item(CreateWorkflow cmd) {
        boolean legacy = blank(cmd.definitionId());
        String definitionId = legacy ? WorkflowTemplates.NOTEBOOK : cmd.definitionId().strip();
        WorkflowTemplate template = templates.find(definitionId).orElseThrow(() -> bad("Unknown workflow definition " + definitionId));
        boolean notebook = WorkflowTemplates.NOTEBOOK.equals(definitionId);
        String category = blank(cmd.categoryCode()) ? (notebook ? "NOTEBOOKS" : null) : cmd.categoryCode().strip();
        if (category == null || !CATEGORY.matcher(category).matches()) {
            throw bad("categoryCode is required: the catalogue category, such as FLIERS");
        }
        String code = blank(cmd.itemCode()) ? (notebook ? "NB" : null) : cmd.itemCode().strip();
        if (code == null || !ITEM_CODE.matcher(code).matches()) {
            throw bad("itemCode is required: the category's 2-4 letter order code, such as FE");
        }
        String noun = blank(cmd.itemNoun())
                ? (notebook ? "notebook" : category.toLowerCase(Locale.ROOT).replace('_', ' '))
                : cmd.itemNoun().strip();
        if (noun.length() > 40) throw bad("itemNoun is limited to 40 characters");
        return new Item(template, category, code, noun);
    }

    private static String orderNumber(String value) {
        if (blank(value)) return null;
        String clean = value.strip();
        if (!ORDER_NUMBER.matcher(clean).matches()) throw bad("orderNumber must be an order number such as NB-DPS_GUR-26-11-13-0042");
        return clean;
    }

    /**
     * Read in a fresh transaction, scoped to the order's school. Order ids and numbers are globally unique, so finding
     * nothing means the order (or its number) is already held under another school, which this scope cannot see.
     */
    private Created existingAfterRace(CreateWorkflow cmd) {
        return tx.execute(status -> {
            repo.applySchoolScope(cmd.schoolId());
            return repo.findByOrder(cmd.orderId(), false).filter(existing -> existing.schoolId() == cmd.schoolId())
                    .map(existing -> new Created(existing.instanceId(), false))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Order is already tracked under another school, or its order number belongs to another order"));
        });
    }

    private Created createInTransaction(CreateWorkflow cmd, Item item, ZoneId zone, String orderNumber, boolean[] statusQueued) {
        return tx.execute(status -> {
            repo.applySchoolScope(cmd.schoolId());
            Optional<TrackerRow> existing = repo.findByOrder(cmd.orderId(), true);
            if (existing.isPresent()) {
                TrackerRow t = existing.get();
                if (t.schoolId() != cmd.schoolId()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to another school");
                if (orderNumber != null && t.orderNumber() == null) {
                    if (repo.orderNumberTaken(orderNumber, cmd.orderId())) throw conflict("Order number " + orderNumber + " belongs to another order");
                    if (repo.fillOrderNumber(t.instanceId(), orderNumber) > 0) statusQueued[0] = reportAgain(t);
                }
                return new Created(t.instanceId(), false);
            }
            if (orderNumber != null && repo.orderNumberTaken(orderNumber, cmd.orderId())) {
                throw conflict("Order number " + orderNumber + " belongs to another order");
            }
            WorkflowTemplate template = item.template();
            String definitionId = template.definitionId();
            List<StepSpec> path = template.pathFor(cmd.customised());
            Map<String, ImportedStep> imported = imported(cmd, path);
            int completed = 0;
            while (completed < path.size() && imported.containsKey(path.get(completed).code())
                    && "COMPLETED".equals(imported.get(path.get(completed).code()).status())) {
                completed++;
            }
            boolean finished = completed == path.size();
            StepSpec current = path.get(finished ? path.size() - 1 : completed);
            long instanceId = repo.insertInstance(cmd.schoolId(), cmd.orderId(), definitionId, current.order(),
                    finished ? "COMPLETED" : "IN_PROGRESS", cmd.placedBy());
            repo.insertTracker(new NewTracker(instanceId, cmd.schoolId(), cmd.orderId(), cmd.customised(), cmd.orderedCount(),
                    cmd.placedAt(), zone.getId(), blank(cmd.schoolName()) ? null : cmd.schoolName().strip(), definitionId,
                    item.categoryCode(), item.itemCode(), item.itemNoun(), orderNumber, imported.isEmpty() ? null : IMPORTED_FROM_V36));
            Map<Integer, Optional<Sla>> slas = effectiveSla(cmd.schoolId(), definitionId);
            OffsetDateTime now = now();
            OffsetDateTime previousEnd = cmd.placedAt();
            for (int index = 0; index < path.size(); index++) {
                StepSpec step = path.get(index);
                if (index < completed) {
                    ImportedStep source = imported.get(step.code());
                    OffsetDateTime done = source.completedAt() == null ? now : source.completedAt();
                    OffsetDateTime started = previousEnd.isAfter(done) ? done : previousEnd;
                    repo.insertImportedStep(instanceId, cmd.schoolId(), definitionId, step.order(), cmd.assigneeUserId(), started, done,
                            source.completedBy());
                    previousEnd = done;
                } else if (index == completed) {
                    // Step 1's clock starts at placement; an imported order's current stage starts now, on the engine.
                    OffsetDateTime start = completed == 0 ? cmd.placedAt() : now;
                    repo.insertStep(instanceId, cmd.schoolId(), definitionId, step.order(), "ACTIVE", cmd.assigneeUserId(), start,
                            due(start, slas.get(step.order()), zone));
                } else {
                    repo.insertStep(instanceId, cmd.schoolId(), definitionId, step.order(), "LOCKED", cmd.assigneeUserId(), null, null);
                }
            }
            Map<String, Object> createdFacts = new LinkedHashMap<>();
            createdFacts.put("definitionId", definitionId);
            createdFacts.put("itemCode", item.itemCode());
            if (orderNumber != null) createdFacts.put("orderNumber", orderNumber);
            repo.recordAction(instanceId, cmd.schoolId(), path.get(0).order(), "TRACKER_CREATED", facts(createdFacts));
            for (int index = 0; index < completed; index++) {
                StepSpec step = path.get(index);
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("source", IMPORTED_FROM_V36);
                source.put("stage", imported.get(step.code()).sourceStageCode());
                repo.recordAction(instanceId, cmd.schoolId(), step.order(), "STEP_IMPORTED", facts(source));
            }
            importNotes(instanceId, cmd, path, imported, now);
            statuses.record(cmd.orderId(), cmd.schoolId(), finished ? WorkflowProgress.COMPLETED : current.code(), repo.instanceVersion(instanceId));
            return new Created(instanceId, true);
        });
    }

    /**
     * A workflow created before order numbers gets its number when school-core re-queues its create (catalog V38, D13). Its
     * earlier status pushes carried no step, so school-core's lists show only the status in words until the next step
     * change (and a finished order never has one). The number is a change of the order, so the instance version moves and
     * the current status is queued again at that newer version: every school-core revision applies it, and the push carries
     * the step report. In the create's own transaction; true when a status was queued. An order whose stages no longer fit
     * its template is left as it is: its number still fills.
     */
    private boolean reportAgain(TrackerRow t) {
        WorkflowTemplate template = templates.find(t.definitionId()).orElse(null);
        if (template == null) return false;
        String current;
        try {
            current = progress(template, t, repo.steps(t.instanceId())).statusCode();
        } catch (IllegalStateException inconsistent) {
            LOG.warn("Order {} gets its number but no status push: {}", t.orderId(), inconsistent.getMessage());
            return false;
        }
        statuses.record(t.orderId(), t.schoolId(), current, repo.bumpInstanceVersion(t.instanceId()));
        return true;
    }

    /** The imported stages that are on the order's path, by code; a stage the template does not know is left out. */
    private static Map<String, ImportedStep> imported(CreateWorkflow cmd, List<StepSpec> path) {
        Map<String, ImportedStep> byCode = new LinkedHashMap<>();
        Set<String> onPath = new HashSet<>();
        for (StepSpec step : path) onPath.add(step.code());
        for (ImportedStep step : cmd.importedProgress()) {
            if (step.stepCode() == null || !STEP_CODE.matcher(step.stepCode()).matches()) throw bad("Each imported stage needs its stepCode");
            if (!"COMPLETED".equals(step.status()) && !"ACTIVE".equals(step.status())) {
                throw bad("An imported stage is COMPLETED or ACTIVE");
            }
            if (step.sourceStageCode() != null && step.sourceStageCode().length() > 64) throw bad("sourceStageCode is limited to 64 characters");
            if (!onPath.contains(step.stepCode())) {
                LOG.warn("Order {}: imported stage {} is not a stage of its workflow and is left out", cmd.orderId(), step.stepCode());
                continue;
            }
            byCode.putIfAbsent(step.stepCode(), step);
        }
        return byCode;
    }

    /** Each imported stage's "Production update" and note become stage notes of that stage (design D4). */
    private void importNotes(long instanceId, CreateWorkflow cmd, List<StepSpec> path, Map<String, ImportedStep> imported, OffsetDateTime now) {
        for (StepSpec step : path) {
            ImportedStep source = imported.get(step.code());
            if (source == null) continue;
            Long author = source.completedBy() != null ? source.completedBy() : cmd.placedBy();
            if (author == null) {
                if (!blank(source.details()) || !blank(source.note())) {
                    LOG.warn("Order {}: imported notes of stage {} have no author and are left out", cmd.orderId(), step.code());
                }
                continue;
            }
            OffsetDateTime at = source.completedAt() == null ? now : source.completedAt();
            if (!blank(source.details())) {
                repo.addNote(instanceId, cmd.schoolId(), author, clip("Production update: " + source.details().strip()), step.order(), at);
            }
            if (!blank(source.note())) repo.addNote(instanceId, cmd.schoolId(), author, clip(source.note().strip()), step.order(), at);
        }
    }

    private static String clip(String text) {
        return text.length() <= NoteRules.NOTE_MAX_CHARS ? text : text.substring(0, NoteRules.NOTE_MAX_CHARS - 1) + "…";
    }

    // ==== design files (machine call from school-core) ==================================================================

    /**
     * Whether this user may upload or delete design files now (design D26, D42). school-core asks before every upload or
     * delete and has already checked the caller's school and item scope; the workflow decides by stage and assignment.
     */
    public DesignFileAccess designFileAccess(String orderId, long userId, String role) {
        return tx.execute(status -> {
            repo.applyBypassScope();
            Optional<TrackerRow> found = repo.findByOrder(orderId, false);
            if (found.isEmpty()) return new DesignFileAccess(false, "This order has no workflow yet");
            TrackerRow t = found.get();
            WorkflowTemplate template = template(t);
            List<StepRow> rows = repo.steps(t.instanceId());
            WorkflowProgress progress = progress(template, t, rows);
            Optional<StepSpec> design = progress.designStep();
            if (design.isEmpty()) return new DesignFileAccess(false, "Design files do not apply to this order");
            Role actor = "SUPERADMIN".equalsIgnoreCase(role) ? Role.SUPERADMIN : "OPERATIONS".equalsIgnoreCase(role) ? Role.OPERATOR : null;
            if (actor == null) return new DesignFileAccess(false, "Design files are managed by superadmin and operators");
            if (actor == Role.OPERATOR && !Objects.equals(userId, byOrder(rows).get(design.get().order()).assigneeUserId())) {
                return new DesignFileAccess(false, design.get().title() + " is assigned to another operator");
            }
            if (!progress.canManageDesignFiles(actor, t.designState())) {
                return new DesignFileAccess(false, "Design files cannot be changed in the current stage");
            }
            return new DesignFileAccess(true, null);
        });
    }

    // ==== reads ==========================================================================================================

    /**
     * The order list and board (design §4.2): every order in the caller's school and item scope that matches the
     * filters, a page of it, and the summary chips. Staff names are added once the read has committed.
     */
    public OrderWorkflowPage list(ListQuery query) {
        ListQuery q = query == null ? ListQuery.all() : query;
        List<String> items = items(q.item());
        String step = optionalPattern(q.step(), STEP_CODE, "step must be a step code such as PRINTING_PRESS");
        String phase = optionalIn(q.phase(), PHASES, "phase must be one of " + new TreeSet<>(PHASES));
        String sla = optionalIn(q.sla(), SLA_FILTERS, "sla must be one of " + new TreeSet<>(SLA_FILTERS));
        String sort = q.sort() == null || q.sort().isBlank() ? "due" : optionalIn(q.sort(), SORTS, "sort must be due, placed or number");
        if (q.schoolId() != null && q.schoolId() <= 0) throw bad("schoolId must be positive");
        String search = blank(q.q()) ? null : q.q().strip();
        if (search != null && search.length() > 100) throw bad("q is limited to 100 characters");
        int limit = q.limit() == null ? DEFAULT_LIMIT : q.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw bad("limit must be between 1 and " + MAX_LIMIT);
        int offset = q.offset() == null ? 0 : q.offset();
        if (offset < 0) throw bad("offset must not be negative");
        OrderWorkflowPage page = tx.execute(status -> {
            Role role = OrderWorkflowAccess.role(false);
            TenantContext ctx = TenantContext.get();
            long me = ctx.userId();
            String assignee = assignee(q.assignee(), role, me);
            repo.applyOperatorScope();
            Scope scope = role == Role.SUPERADMIN ? new Scope(null, null) : new Scope(ctx.operatorSchools(), ctx.operatorItemScopes());
            OffsetDateTime now = now();
            ListFilter filter = new ListFilter(scope, items, step, phase, assignee, q.schoolId(), sla, search,
                    Boolean.TRUE.equals(q.includeFinished()), sort, limit, offset, now, now.plus(Sla.DUE_SOON_WITHIN));
            List<OrderWorkflowListItem> rows = repo.list(filter).stream().map(row -> listItem(row, now)).toList();
            long total = repo.count(filter);
            OrderWorkflowRepository.Counts counts = repo.counts(filter, me);
            return new OrderWorkflowPage(rows, total, new OrderWorkflowCounts(counts.open(), counts.overdue(), counts.dueToday(),
                    counts.unassigned(), counts.mine()), now);
        });
        return page.withStaffNames(names.lookup(page.staffIds()));
    }

    private static List<String> items(String item) {
        if (blank(item)) return List.of();
        String clean = item.strip();
        if (clean.length() > 200 || !ITEM_LIST.matcher(clean).matches()) throw bad("item must be comma-separated item codes such as NB,FE");
        return List.of(clean.split(","));
    }

    /** {@code me}, {@code unassigned}, or a user id; an operator may name only themselves. */
    private static String assignee(String value, Role role, long me) {
        if (blank(value)) return null;
        String clean = value.strip();
        if ("me".equals(clean)) return String.valueOf(me);
        if ("unassigned".equals(clean)) return clean;
        if (!clean.matches("[1-9][0-9]{0,18}")) throw bad("assignee must be me, unassigned or a user id");
        if (role != Role.SUPERADMIN && Long.parseLong(clean) != me) {
            throw forbidden("Only superadmin can filter by another operator");
        }
        return clean;
    }

    private OrderWorkflowListItem listItem(ListRow row, OffsetDateTime now) {
        WorkflowTemplate template = templates.find(row.definitionId()).orElse(null);
        boolean finished = row.activeStep() == null;
        StepSpec current = finished || template == null ? null : template.step(row.activeStep()).orElse(null);
        String status = finished ? WorkflowProgress.COMPLETED : current != null ? current.code() : row.stepCode();
        String phase;
        if (current != null) {
            phase = current.phase().name();
        } else if (finished && template != null) {
            List<StepSpec> path = template.pathFor(row.customised());
            phase = path.get(path.size() - 1).phase().name();
        } else {
            phase = row.phase() == null ? "INTAKE" : row.phase();
        }
        String title = finished ? null : current != null ? current.title() : row.stepTitle();
        OffsetDateTime dueAt = finished ? null : row.dueAt();
        boolean overdue = dueAt != null && now.isAfter(dueAt);
        String slaState = Sla.state(dueAt == null ? null : dueAt.toInstant(), now.toInstant()).name();
        return new OrderWorkflowListItem(row.orderId(), row.orderNumber(), row.definitionId(), row.categoryCode(), row.itemCode(),
                row.schoolId(), row.schoolName(), row.customised(), row.orderedCount(), row.placedAt(), row.timeZone(), status, phase,
                finished ? null : row.activeStep(), title, finished ? null : row.assigneeUserId(), null, dueAt, overdue, slaState,
                row.completed(), row.total());
    }

    /** The workflow screen of one order; design files are counted and staff names added once the read has committed. */
    public OrderWorkflowView view(String orderId) {
        Assembled assembled = tx.execute(status -> {
            Cleared cleared = access.clear(orderId, false, false);
            return assemble(cleared.role(), cleared.userId(), cleared.tracker());
        });
        return finish(assembled);
    }

    /** Every template, with its current default time slots (design D2). */
    public List<WorkflowTemplate> templates() {
        return tx.execute(status -> {
            OrderWorkflowAccess.role(false);
            return templates.all().stream().map(this::withCurrentSlas).toList();
        });
    }

    public WorkflowTemplate template(String definitionId) {
        return tx.execute(status -> {
            OrderWorkflowAccess.role(false);
            return withCurrentSlas(knownTemplate(definitionId));
        });
    }

    /** Superadmin changes a template step's default time slot; it applies to stages that start afterwards (D23). */
    public WorkflowTemplate setDefaultSla(String definitionId, int step, Sla sla) {
        return tx.execute(status -> {
            Role role = OrderWorkflowAccess.role(true);
            requireSuperadmin(role, "Only superadmin can change a template's time slots");
            WorkflowTemplate template = knownTemplate(definitionId);
            if (template.step(step).isEmpty()) throw notFound("Step " + step + " is not a step of " + template.title());
            if (repo.updateDefaultSla(definitionId, step, sla, TenantContext.get().userId()) == 0) {
                throw notFound("Step " + step + " is not a step of " + template.title());
            }
            return withCurrentSlas(template);
        });
    }

    private WorkflowTemplate knownTemplate(String definitionId) {
        return templates.find(definitionId == null ? "" : definitionId.strip())
                .orElseThrow(() -> notFound("Unknown workflow template " + definitionId));
    }

    private WorkflowTemplate withCurrentSlas(WorkflowTemplate template) {
        Map<Integer, Optional<Sla>> defaults = new HashMap<>();
        for (DefinitionStep step : repo.definitionSteps(template.definitionId())) defaults.put(step.stepOrder(), step.defaultSla());
        return template.withDefaultSlas(defaults);
    }

    // ==== stage work =====================================================================================================

    /**
     * A partial save of a stage's fields and checklists (design §3.5 {@code PUT .../data}): the keys sent replace the saved
     * ones, a key sent blank is cleared and a key left out is kept ({@link StepDataRules#merge}). The ACTIVE stage is saved
     * by its assignee or superadmin, a COMPLETED one by superadmin only, a LOCKED one by nobody (D18). A change voids the
     * stage's sign-off (D20); on a COMPLETED stage only a change its gates read does. A changed CDR link makes the Head
     * Approval screen reload before deciding (D21). Ticking the introduction records the message as sent (D28).
     */
    public OrderWorkflowView saveStepData(String orderId, int step, Map<String, Object> data, long expectedVersion) {
        if (data == null) throw bad("data is required");
        return mutate(orderId, work -> {
            StepRow row = work.row(step);
            StepSpec spec = work.progress.step(step);
            requireWorker(work, row, spec);
            work.progress.requireEditable(work.role, step);
            // A finished stage keeps its sign-off for changes no gate reads, such as the invoice moving on after delivery.
            Map<String, Object> merged = StepDataRules.merge(spec, row.data(), data, work.progress.status(step) == StepStatus.COMPLETED);
            requireVersion(row, expectedVersion);
            // A save of the values already saved (an autosave repeating itself) changes nothing and records nothing.
            if (StepDataRules.sameValues(row.data(), merged) && StepDataRules.signedOff(row.data()) == StepDataRules.signedOff(merged)) return;
            requireSaved(repo.saveStepData(work.t.instanceId(), step, merged, work.userId, expectedVersion));
            Map<String, Object> before = StepDataRules.visible(row.data());
            Map<String, Object> after = StepDataRules.visible(merged);
            List<String> changed = changedKeys(before, after);
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("fields", changed.stream().filter(key -> !spec.checklists().containsKey(key)).toList());
            Map<String, String> checklists = new LinkedHashMap<>();
            for (Map.Entry<String, ChecklistSpec> checklist : spec.checklists().entrySet()) {
                if (!changed.contains(checklist.getKey())) continue;
                checklists.put(checklist.getKey(), StepDataRules.ticked(merged, checklist.getKey()).size() + "/" + checklist.getValue().size());
            }
            if (!checklists.isEmpty()) saved.put("checklists", checklists);
            if (StepDataRules.signedOff(row.data()) && !StepDataRules.signedOff(merged)) saved.put("signOffVoided", true);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), step, "STEP_DATA_SAVED", facts(saved));
            introSent(work, spec, row.data(), merged);
            if (changed.contains(CDR_LINK_FIELD)) {
                // Head Approval binds the link (D21): an approver whose screen shows the old link must reload first.
                headApprovalStep(work.progress).filter(approval -> work.progress.status(approval.order()) != StepStatus.LOCKED)
                        .ifPresent(approval -> repo.bumpStep(work.t.instanceId(), approval.order()));
            }
        });
    }

    /**
     * Ticking the introduction checklist records who sent the introduction, when, and the final text (design D28,
     * {@code INTRO_SENT}): the message the screen saved with the tick into the card's field, with the sender's name and
     * any edits. A client that sends none (an older screen) records the template's text with its item named.
     */
    private void introSent(Work work, StepSpec spec, Map<String, Object> before, Map<String, Object> after) {
        for (BlockSpec block : spec.blocks()) {
            if (block.type() != BlockType.INTRO_MESSAGE || block.checklist() == null) continue;
            ChecklistSpec checklist = spec.checklists().get(block.checklist());
            if (checklist == null) continue;
            boolean wasComplete = StepDataRules.ticked(before, block.checklist()).size() >= checklist.size();
            boolean complete = StepDataRules.ticked(after, block.checklist()).size() >= checklist.size();
            if (wasComplete || !complete) continue;
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), spec.order(), "INTRO_SENT", introText(work, block, after));
        }
    }

    /** The introduction as sent: the text saved in the card's field, else the template's message with its item named. */
    private static String introText(Work work, BlockSpec block, Map<String, Object> data) {
        for (String key : block.fields() == null ? List.<String>of() : block.fields()) {
            String sent = StepDataRules.text(data, key);
            if (sent != null) return sent;
        }
        return work.template.introMessage() == null ? null : work.template.introMessage().replace("{Item}", work.t.itemNoun());
    }

    /**
     * Completes the active stage (design D18, D19): refused with {@code GATE_UNMET} listing every unmet requirement. The
     * next stage's clock starts now. The design-file count a gate needs is asked of the catalogue before the order is
     * locked, and the gates are checked again under the lock.
     */
    public OrderWorkflowView completeStep(String orderId, int step, long expectedVersion) {
        Integer designFiles = designFilesFor(orderId, step);
        return mutate(orderId, work -> {
            StepRow row = work.row(step);
            StepSpec spec = work.progress.step(step);
            requireWorker(work, row, spec);
            WorkflowProgress next = work.progress.complete(step, work.role,
                    facts(work.t, spec, row.data(), currentCdrLink(work.progress, work.byOrder), designFiles == null ? 0 : designFiles));
            requireVersion(row, expectedVersion);
            requireSaved(repo.completeStep(work.t.instanceId(), step, work.userId, expectedVersion));
            Optional<StepSpec> following = next.current();
            if (following.isPresent()) {
                StepSpec upcoming = following.get();
                OffsetDateTime start = now();
                repo.activateStep(work.t.instanceId(), upcoming.order(), start, due(start, slaOf(work, upcoming.order()), zone(work.t)));
                repo.setInstanceProgress(work.t.instanceId(), upcoming.order(), "IN_PROGRESS", false);
                if (upcoming.hasGate(GateType.HEAD_APPROVED)) repo.markDesignSent(work.t.instanceId());
            } else {
                repo.setInstanceProgress(work.t.instanceId(), step, "COMPLETED", true);
            }
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), step, "STEP_COMPLETED", null);
        });
    }

    /** The design files the completion of {@code step} needs counted, asked with no transaction open; null when none. */
    private Integer designFilesFor(String orderId, int step) {
        String order = tx.execute(status -> {
            Cleared cleared = access.clear(orderId, true, false);
            TrackerRow t = cleared.tracker();
            WorkflowProgress progress = progress(template(t), t, repo.steps(t.instanceId()));
            if (!progress.onPath(step) || !progress.step(step).hasGate(GateType.DESIGN_FILES_PRESENT)) return null;
            return t.orderId();
        });
        if (order == null) return null;
        try {
            return catalog.draftImages(order).size();
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The order catalogue is unavailable; try again");
        }
    }

    /**
     * Superadmin reopens a completed stage, also as "Send back to Designing Activity" from Pre-Print Quality Check (D22).
     * The stage restarts its clock; later stages lock with their data kept, and what they had signed off is taken back.
     */
    public OrderWorkflowView reopenStep(String orderId, int step, String reason, long expectedVersion) {
        String clean = NoteRules.required(reason, NoteRules.REASON_MAX_WORDS, NoteRules.REASON_MAX_CHARS, "A reason");
        return mutate(orderId, work -> {
            requireSuperadmin(work.role, "Only superadmin can reopen a completed stage");
            StepRow row = work.row(step);
            requireVersion(row, expectedVersion);
            boolean sendBack = work.progress.current().map(current -> current.hasBlock(BlockType.SEND_BACK)).orElse(false)
                    && work.progress.designStep().map(design -> design.order() == step).orElse(false);
            reopenTo(work, step);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), step, sendBack ? "SENT_BACK" : "STEP_REOPENED", clean);
        });
    }

    /** Reopens {@code step}: later stages lock, the stage restarts its clock, reverted sign-offs and approvals go. */
    private void reopenTo(Work work, int step) {
        work.progress.reopen(step, work.role);
        List<StepSpec> affected = new ArrayList<>();
        affected.add(work.progress.step(step));
        affected.addAll(work.progress.revertedBy(step));
        // Lock the later stages (the active one among them) before activating this one: one active stage per order (V11).
        repo.lockStepsAfter(work.t.instanceId(), step);
        OffsetDateTime start = now();
        repo.activateStep(work.t.instanceId(), step, start, due(start, slaOf(work, step), zone(work.t)));
        for (StepSpec spec : affected) {
            Map<String, Object> data = work.byOrder.get(spec.order()).data();
            if (spec.hasSignOff() && StepDataRules.signedOff(data)) {
                repo.replaceStepData(work.t.instanceId(), spec.order(), StepDataRules.clearSignOff(spec, data));
            }
        }
        Optional<StepSpec> approval = headApprovalStep(work.progress);
        if (approval.isPresent() && step <= approval.get().order()) repo.withdrawDesignApproval(work.t.instanceId(), work.userId);
        repo.setInstanceProgress(work.t.instanceId(), step, "IN_PROGRESS", false);
    }

    /**
     * Superadmin records a stage's sign-off (QC reviewer, internal approval, consignment; D20). It approves the data
     * superadmin saw, so it carries the stage version their screen loaded; it ticks the checklist's sign-off item.
     */
    public OrderWorkflowView signOff(String orderId, int step, long expectedVersion) {
        return mutate(orderId, work -> {
            StepRow row = work.row(step);
            StepSpec spec = work.progress.step(step);
            if (!spec.hasSignOff()) throw new WorkflowRuleViolation(NO_SIGN_OFF, spec.title() + " has no sign-off");
            requireSuperadmin(work.role, "Only superadmin can record a sign-off");
            if (!work.progress.canSignOff(work.role, step)) {
                work.progress.requireEditable(work.role, step);
                throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE, spec.title() + " cannot be signed off now");
            }
            requireVersion(row, expectedVersion);
            Map<String, Object> signed = StepDataRules.applySignOff(spec, row.data(), work.userId, now().toInstant());
            requireSaved(repo.saveStepData(work.t.instanceId(), step, signed, work.userId, expectedVersion));
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), step, "STEP_SIGNED_OFF", null);
        });
    }

    // ==== Head Approval (D21) ============================================================================================

    /**
     * Superadmin approves the CDR link saved in CDR Conversion now, with a required comment. The approval binds that link:
     * if it changes later the approval no longer counts. {@code expectedVersion} is Head Approval's stage version; a
     * screen that loaded before the link changed is refused with {@code HEAD_APPROVAL_STALE}.
     */
    public OrderWorkflowView approveDesign(String orderId, String comment, long expectedVersion) {
        String clean = NoteRules.required(comment, NoteRules.APPROVAL_COMMENT_MAX_WORDS, NoteRules.APPROVAL_COMMENT_MAX_CHARS,
                "Approval comments");
        return mutate(orderId, work -> {
            StepSpec approval = openHeadApproval(work, expectedVersion);
            String link = currentCdrLink(work.progress, work.byOrder);
            if (link == null) {
                throw new WorkflowRuleViolation(CDR_LINK_MISSING, "No CDR link saved yet. Go back to CDR Conversion and save the link first.");
            }
            requireSaved(repo.touchStep(work.t.instanceId(), approval.order(), expectedVersion));
            repo.approveDesign(work.t.instanceId(), clean, work.userId, link);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), approval.order(), "HEAD_APPROVED", clean);
        });
    }

    /**
     * Superadmin requests changes, with a required comment: the design state becomes {@code CHANGES_REQUESTED} and the
     * order returns to Designing Activity, whose clock restarts; the stages after it lock with their data kept.
     */
    public OrderWorkflowView requestDesignChanges(String orderId, String comment, long expectedVersion) {
        String clean = NoteRules.required(comment, NoteRules.APPROVAL_COMMENT_MAX_WORDS, NoteRules.APPROVAL_COMMENT_MAX_CHARS,
                "Approval comments");
        return mutate(orderId, work -> {
            StepSpec approval = openHeadApproval(work, expectedVersion);
            StepSpec design = work.progress.designStep().orElseThrow(() -> new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_ON_PATH,
                    "This order has no design stage to return to"));
            requireSaved(repo.touchStep(work.t.instanceId(), approval.order(), expectedVersion));
            repo.requestDesignChanges(work.t.instanceId(), clean, work.userId);
            reopenTo(work, design.order());
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), approval.order(), WorkflowProgress.CHANGES_REQUESTED, clean);
        });
    }

    private StepSpec openHeadApproval(Work work, long expectedVersion) {
        requireSuperadmin(work.role, "Only superadmin decides Head Approval");
        StepSpec approval = headApprovalStep(work.progress).orElseThrow(() -> new WorkflowRuleViolation(
                WorkflowRuleViolation.STEP_NOT_ON_PATH, "Head Approval does not apply to this order"));
        if (work.progress.status(approval.order()) != StepStatus.ACTIVE) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_ACTIVE, approval.title() + " is not the active stage");
        }
        if (work.byOrder.get(approval.order()).version() != expectedVersion) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.HEAD_APPROVAL_STALE,
                    "The design changed since you loaded it; reload to see the current CDR link");
        }
        return approval;
    }

    // ==== assignment =====================================================================================================

    /** Superadmin assigns one stage that is not completed to an eligible operator. */
    public OrderWorkflowView assignStep(String orderId, int step, long userId) {
        requireEligibleOperator(orderId, userId, true);
        return mutate(orderId, work -> {
            requireSuperadmin(work.role, "Only superadmin can assign work");
            work.row(step);
            if (work.progress.status(step) == StepStatus.COMPLETED) {
                throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE, work.progress.step(step).title()
                        + " is completed and cannot be reassigned");
            }
            repo.assignStep(work.t.instanceId(), step, userId, work.userId);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), step, "STEP_ASSIGNED", facts(Map.of("userId", userId)));
        });
    }

    /** Superadmin assigns every stage that is not completed (the order's point of contact, D37). */
    public OrderWorkflowView assignOrder(String orderId, long userId) {
        requireEligibleOperator(orderId, userId, true);
        return mutate(orderId, work -> {
            requireSuperadmin(work.role, "Only superadmin can assign work");
            int first = assignOpenSteps(work, userId);
            if (first < 0) throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_ACTIVE, "Every stage is completed");
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("userId", userId);
            facts.put("scope", "ORDER");
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), first, "STEP_ASSIGNED", facts(facts));
        });
    }

    /**
     * "Take this order" (D37): an eligible operator takes an order nobody holds (operator resolution at creation found
     * none, or several). Every open stage becomes theirs.
     */
    public OrderWorkflowView claim(String orderId) {
        Long me = TenantContext.get().userId();
        if (me == null) throw forbidden("Authenticated user required");
        requireEligibleOperator(orderId, me, false);
        return mutate(orderId, work -> {
            if (work.role != Role.OPERATOR) throw conflict("Superadmin assigns orders; taking an order is for operators");
            if (!unassigned(work.progress, work.rows)) {
                throw new WorkflowRuleViolation(ORDER_ASSIGNED, "This order already has an operator");
            }
            int first = assignOpenSteps(work, work.userId);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), first, "ORDER_CLAIMED", null);
        });
    }

    private int assignOpenSteps(Work work, long userId) {
        int first = -1;
        for (StepRow row : work.rows) {
            if ("COMPLETED".equals(row.status())) continue;
            if (first < 0) first = row.stepOrder();
            repo.assignStep(work.t.instanceId(), row.stepOrder(), userId, work.userId);
        }
        return first;
    }

    /** No open stage has an assignee, and the order is not finished. */
    private static boolean unassigned(WorkflowProgress progress, List<StepRow> rows) {
        return !progress.finished() && rows.stream().filter(row -> !"COMPLETED".equals(row.status())).allMatch(row -> row.assigneeUserId() == null);
    }

    /**
     * Identity decides who is an eligible operator for the order's school and item. It is asked before the transaction
     * opens so a slow peer never holds a row lock, and a peer that cannot answer refuses instead of letting it through.
     * For an assignment the caller must be superadmin; for a claim, an operator who can work the order.
     */
    private void requireEligibleOperator(String orderId, long userId, boolean byAssignment) {
        if (userId <= 0) throw bad("userId is required");
        TrackerRow t = tx.execute(status -> {
            Cleared cleared = access.clear(orderId, true, false);
            if (byAssignment) requireSuperadmin(cleared.role(), "Only superadmin can assign work");
            else if (cleared.role() != Role.OPERATOR) throw conflict("Superadmin assigns orders; taking an order is for operators");
            if (!byAssignment) {
                TrackerRow row = cleared.tracker();
                List<StepRow> rows = repo.steps(row.instanceId());
                if (!unassigned(progress(template(row), row, rows), rows)) {
                    throw new WorkflowRuleViolation(ORDER_ASSIGNED, "This order already has an operator");
                }
            }
            return cleared.tracker();
        });
        boolean eligible;
        try {
            eligible = eligibility.eligible(userId, t.schoolId(), t.categoryCode());
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operator directory is unavailable; try again");
        }
        if (!eligible) {
            if (byAssignment) throw bad("User is not an eligible operator for this school and item");
            throw forbidden("You are not an eligible operator for this school and item");
        }
    }

    // ==== vendors, designer, contacts ====================================================================================

    /**
     * The order's vendors per category (D33): chosen at Production Planning by its assignee while it is active, or by
     * superadmin at any time. A notebook order picks from its four categories; other items from every category. A vendor
     * the order already holds stays selectable after it is deactivated; a newly chosen one must be active.
     * {@code expectedVersion} is the order's version.
     */
    public OrderWorkflowView setVendorSelections(String orderId, Map<String, List<Long>> selections, long expectedVersion) {
        if (selections == null) throw bad("selections is required");
        return mutate(orderId, work -> {
            StepSpec planning = work.progress.path().stream().filter(step -> step.hasBlock(BlockType.VENDOR_SELECTION)).findFirst()
                    .orElseThrow(() -> conflict("This order has no vendor selection"));
            if (work.role != Role.SUPERADMIN) {
                if (work.progress.status(planning.order()) != StepStatus.ACTIVE) {
                    throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE,
                            "Vendors are selected while " + planning.title() + " is active");
                }
                requireAssignee(work, work.byOrder.get(planning.order()), planning);
            }
            Set<String> allowed = selectableCategories(planning);
            Map<String, List<Long>> clean = new TreeMap<>();
            Set<Long> fresh = new LinkedHashSet<>();
            for (Map.Entry<String, List<Long>> entry : selections.entrySet()) {
                String category = entry.getKey();
                if (category == null || !allowed.contains(category)) throw bad("Unknown or unavailable vendor category: " + category);
                List<Long> ids = entry.getValue();
                if (ids == null) throw bad("The vendors of " + category + " cannot be null");
                if (ids.stream().anyMatch(Objects::isNull)) throw bad("A vendor id cannot be null");
                if (new HashSet<>(ids).size() != ids.size()) throw bad("A vendor is selected twice in " + category);
                if (ids.size() > MAX_VENDORS_PER_CATEGORY) throw bad("At most " + MAX_VENDORS_PER_CATEGORY + " vendors per category");
                List<Long> held = work.t.vendorSelections().getOrDefault(category, List.of());
                for (Long id : ids) {
                    if (!vendors.inCategory(id, category)) throw bad("Vendor " + id + " is not in " + category);
                    if (!held.contains(id)) fresh.add(id);
                }
                if (!ids.isEmpty()) clean.put(category, ids.stream().sorted().toList());
            }
            if (!fresh.isEmpty()) {
                for (VendorSummary vendor : vendors.vendors(fresh)) {
                    if (!vendor.active()) throw bad(vendor.name() + " is inactive and cannot be newly selected");
                }
            }
            if (repo.setVendorSelections(work.t.instanceId(), clean, expectedVersion) == 0) {
                throw new WorkflowRuleViolation(VERSION_CONFLICT, "This order changed since you loaded it; reload and try again");
            }
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), planning.order(), "VENDORS_SELECTED",
                    facts(Map.of("selections", clean)));
        });
    }

    /** The selection card's fixed categories (NB), or every category of the directory. */
    private Set<String> selectableCategories(StepSpec planning) {
        for (BlockSpec block : planning.blocks()) {
            if (block.type() == BlockType.VENDOR_SELECTION && block.categories() != null) return new HashSet<>(block.categories());
        }
        return vendors.categoryCodes();
    }

    /** The current stage's assignee or superadmin chooses the designer (D36); null clears it. */
    public OrderWorkflowView setDesigner(String orderId, Long designerId) {
        if (designerId != null && designerId <= 0) throw bad("designerId must be positive");
        return mutate(orderId, work -> {
            requireCurrentWorker(work, "choose the designer");
            if (work.progress.designStep().isEmpty()) throw conflict("Designers apply only to orders with a design stage");
            if (designerId != null && !designerId.equals(work.t.designerId())) {
                repo.designer(designerId).filter(DesignerRow::active).orElseThrow(() -> bad("Designer not found or inactive"));
            }
            if (Objects.equals(designerId, work.t.designerId())) return;
            repo.setDesigner(work.t.instanceId(), designerId);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("designerId", designerId);
            repo.recordAction(work.t.instanceId(), work.t.schoolId(), currentOrLast(work), "DESIGNER_SET", facts(facts));
        });
    }

    /**
     * The order's school contact and factory contact (D36), by the current stage's assignee or superadmin. A factory
     * contact has a name and a phone or neither.
     */
    public OrderWorkflowView setContacts(String orderId, ContactsChange change) {
        if (change == null) throw bad("The contacts are required");
        String schoolName = text(change.schoolContactName(), 200, "The school contact's name");
        String schoolPhone = phone(change.schoolContactPhone(), "The school contact's phone");
        String factoryName = text(change.factoryContactName(), 200, "The factory contact's name");
        String factoryPhone = phone(change.factoryContactPhone(), "The factory contact's phone");
        if ((factoryName == null) != (factoryPhone == null)) throw bad("Enter both the factory contact's name and phone, or neither");
        return mutate(orderId, work -> {
            requireCurrentWorker(work, "change the order's contacts");
            TrackerRow t = work.t;
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(schoolName, t.schoolContactName())) changed.add("schoolContactName");
            if (!Objects.equals(schoolPhone, t.schoolContactPhone())) changed.add("schoolContactPhone");
            if (!Objects.equals(factoryName, t.vendorName())) changed.add("factoryContactName");
            if (!Objects.equals(factoryPhone, t.vendorPhone())) changed.add("factoryContactPhone");
            if (changed.isEmpty()) return;
            repo.setContacts(t.instanceId(), schoolName, schoolPhone, factoryName, factoryPhone);
            repo.recordAction(t.instanceId(), t.schoolId(), currentOrLast(work), "CONTACTS_SAVED", facts(Map.of("fields", changed)));
        });
    }

    // ==== notes (D38) ====================================================================================================

    /**
     * Appends a stage note ({@code stepOrder} set) or an order-level internal note: anyone who can work the order, at most
     * 100 words. A stage that has not been reached takes no notes.
     */
    public NoteEntry addNote(String orderId, String body, Integer stepOrder) {
        NoteEntry entry = tx.execute(status -> {
            Cleared cleared = access.clear(orderId, true, false);
            TrackerRow t = cleared.tracker();
            String what = stepOrder == null ? "An internal note" : "A stage note";
            int words = stepOrder == null ? NoteRules.INTERNAL_NOTE_MAX_WORDS : NoteRules.STAGE_NOTE_MAX_WORDS;
            String clean = NoteRules.required(body, words, NoteRules.NOTE_MAX_CHARS, what);
            if (stepOrder != null) {
                WorkflowProgress progress = progress(template(t), t, repo.steps(t.instanceId()));
                StepSpec step = progress.step(stepOrder);
                if (!progress.canAddNote(stepOrder)) {
                    throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE, step.title() + " has not been reached yet");
                }
            }
            NoteRow row = repo.addNote(t.instanceId(), t.schoolId(), cleared.userId(), clean, stepOrder, null);
            return new NoteEntry(row.id(), row.stepOrder(), row.authorUserId(), null, row.body(), row.createdAt());
        });
        return entry.withName(names.lookup(List.of(entry.authorUserId())));
    }

    // ==== designers (global, superadmin-maintained) and time slots =======================================================

    /** Superadmin sees every designer; an operator only the active ones. */
    public List<DesignerRow> designers() {
        return tx.execute(status -> repo.designers(OrderWorkflowAccess.role(false) != Role.SUPERADMIN));
    }

    public DesignerRow createDesigner(String name, String phone) {
        return tx.execute(status -> {
            requireSuperadmin(OrderWorkflowAccess.role(true), "Only superadmin can maintain designers");
            String n = designerName(name);
            String p = designerPhone(phone);
            long id;
            try {
                id = repo.insertDesigner(n, p, TenantContext.get().userId());
            } catch (DuplicateKeyException duplicate) {
                throw conflict("A designer with this name and phone already exists");
            }
            return repo.designer(id).orElseThrow();
        });
    }

    public DesignerRow updateDesigner(long id, String name, String phone, boolean active) {
        return tx.execute(status -> {
            requireSuperadmin(OrderWorkflowAccess.role(true), "Only superadmin can maintain designers");
            String n = designerName(name);
            String p = designerPhone(phone);
            int updated;
            try {
                updated = repo.updateDesigner(id, n, p, active, TenantContext.get().userId());
            } catch (DuplicateKeyException duplicate) {
                throw conflict("A designer with this name and phone already exists");
            }
            if (updated == 0) throw notFound("Designer not found");
            return repo.designer(id).orElseThrow();
        });
    }

    private static String designerName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 200) throw bad("A designer's name is required (200 characters at most)");
        return clean;
    }

    private static String designerPhone(String phone) {
        String clean = phone == null ? "" : phone.strip();
        if (clean.isEmpty() || clean.length() > 32) throw bad("A designer's phone is required (32 characters at most)");
        return clean;
    }

    /**
     * The time slot of every step of one template for a school (design D23): its override when set (an empty override is
     * "no timer"), otherwise the template default. Superadmin, or an operator who works some item at the school.
     */
    public List<TimeSlotRow> timeSlots(long schoolId, String definitionId) {
        if (schoolId <= 0) throw bad("A positive schoolId is required");
        return tx.execute(status -> {
            OrderWorkflowAccess.role(false);
            requireSchoolAccess(schoolId);
            repo.applyOperatorScope();
            WorkflowTemplate template = timeSlotTemplate(definitionId);
            Map<Integer, Optional<Sla>> defaults = new HashMap<>();
            for (DefinitionStep step : repo.definitionSteps(template.definitionId())) defaults.put(step.stepOrder(), step.defaultSla());
            Map<Integer, Optional<Sla>> overrides = overrides(schoolId, template.definitionId());
            return template.steps().stream().map(step -> {
                Optional<Sla> fallback = defaults.getOrDefault(step.order(), Optional.ofNullable(step.defaultSla()));
                Optional<Sla> override = overrides.get(step.order());
                EffectiveSla effective = override != null ? override.map(sla -> effective(sla, "SCHOOL")).orElse(null)
                        : fallback.map(sla -> effective(sla, "DEFAULT")).orElse(null);
                return new TimeSlotRow(step.order(), step.code(), step.title(), fallback.orElse(null), effective);
            }).toList();
        });
    }

    /** Superadmin only. A null slot stores "no timer for this school and step". */
    public void setTimeSlot(long schoolId, String definitionId, int step, Sla sla) {
        if (schoolId <= 0) throw bad("A positive schoolId is required");
        tx.executeWithoutResult(status -> {
            requireSuperadmin(OrderWorkflowAccess.role(true), "Only superadmin can change time slots");
            WorkflowTemplate template = timeSlotTemplate(definitionId);
            if (template.step(step).isEmpty()) throw bad("Unknown step " + step + " of " + template.title());
            repo.upsertSchoolSla(schoolId, template.definitionId(), step, sla, TenantContext.get().userId());
        });
    }

    /** Superadmin only: removes the school override so the template default applies again ("Use default"). */
    public void clearTimeSlot(long schoolId, String definitionId, int step) {
        if (schoolId <= 0) throw bad("A positive schoolId is required");
        tx.executeWithoutResult(status -> {
            requireSuperadmin(OrderWorkflowAccess.role(true), "Only superadmin can change time slots");
            WorkflowTemplate template = timeSlotTemplate(definitionId);
            repo.deleteSchoolSla(schoolId, template.definitionId(), step);
        });
    }

    private WorkflowTemplate timeSlotTemplate(String definitionId) {
        if (blank(definitionId)) throw bad("definitionId is required");
        return templates.find(definitionId.strip()).orElseThrow(() -> bad("Unknown workflow template " + definitionId));
    }

    /** Superadmin, or an operator who works some item at the school. */
    private static void requireSchoolAccess(long schoolId) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isSuperAdmin() && !ctx.operatorSchoolsForAnyItem().contains(schoolId)) throw forbidden("No access to this school");
    }

    // ==== a school's own WhatsApp groups (by school) =====================================================================

    /**
     * The school's own groups (SCHOOL_GROUP, INTERNAL_APPROVAL) as target to invite link: superadmin, or an operator
     * assigned to the whole school. Item-level groups and vendor groups are read through the order (design D34).
     */
    public Map<String, String> schoolWhatsAppGroupLinks(long schoolId) {
        return tx.execute(status -> {
            OrderWorkflowAccess.role(false, WHATSAPP_STAFF_ONLY);
            if (schoolId <= 0) throw bad("A positive schoolId is required");
            requireSchoolWideAccess(schoolId);
            repo.applySchoolScope(schoolId);
            return schoolGroups(repo.whatsappGroupLinks(schoolId));
        });
    }

    /** Superadmin only; SCHOOL_GROUP or INTERNAL_APPROVAL, and a blank link removes the group. */
    public Map<String, String> setSchoolWhatsAppGroupLink(long schoolId, String targetKey, String inviteUrl) {
        Role role = OrderWorkflowAccess.role(true, WHATSAPP_STAFF_ONLY);
        requireSuperadmin(role, "Only superadmin can maintain WhatsApp group links");
        String target = targetKey == null ? "" : targetKey.strip();
        if (!SCHOOL_WHATSAPP_TARGETS.contains(target)) throw bad("Choose the school group or the internal approval group");
        String clean = canonicalInvite(inviteUrl);
        if (schoolId <= 0) throw bad("A positive schoolId is required");
        long userId = TenantContext.get().userId();
        return tx.execute(status -> {
            if (clean == null) repo.deleteWhatsAppGroupLink(schoolId, target);
            else repo.upsertWhatsAppGroupLink(schoolId, target, clean, userId);
            return schoolGroups(repo.whatsappGroupLinks(schoolId));
        });
    }

    private static Map<String, String> schoolGroups(Map<String, String> links) {
        Map<String, String> kept = new LinkedHashMap<>(links);
        kept.keySet().retainAll(SCHOOL_WHATSAPP_TARGETS);
        return kept;
    }

    /** Superadmin, or an operator assigned to the whole school; item scopes count for nothing here. */
    private static void requireSchoolWideAccess(long schoolId) {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isSuperAdmin() && !ctx.operatorSchools().contains(schoolId)) throw forbidden("No access to this school");
    }

    /** The only origin an invite link is stored with: V15's {@code invite_url} check spells it exactly so. */
    private static final String WHATSAPP_INVITE_ORIGIN = "https://chat.whatsapp.com";

    /**
     * An invite link however its scheme and host are cased, and with an empty port, answered with the one canonical
     * spelling; null for blank. The invite code itself is kept exactly as given (it is case-sensitive).
     *
     * @throws WorkflowRuleViolation {@code INVALID_INVITE} for anything else
     */
    static String canonicalInvite(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        WorkflowRuleViolation invalid = new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_INVITE,
                "Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)");
        if (clean.length() > 2048) throw invalid;
        try {
            URI uri = new URI(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"chat.whatsapp.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_-]{10,64}")) {
                throw invalid;
            }
            return WHATSAPP_INVITE_ORIGIN + uri.getRawPath();
        } catch (URISyntaxException malformed) {
            throw invalid;
        }
    }

    // ==== the change machinery ===========================================================================================

    /** One change's view of the order, loaded under its lock. */
    private final class Work {
        final Role role;
        final long userId;
        final TrackerRow t;
        final WorkflowTemplate template;
        final List<StepRow> rows;
        final Map<Integer, StepRow> byOrder;
        final WorkflowProgress progress;

        Work(Cleared cleared) {
            this.role = cleared.role();
            this.userId = cleared.userId();
            this.t = cleared.tracker();
            this.template = template(t);
            this.rows = repo.steps(t.instanceId());
            this.byOrder = byOrder(rows);
            this.progress = progress(template, t, rows);
        }

        /** The stage's row; refused with {@code STEP_NOT_ON_PATH} when the stage does not apply to this order. */
        StepRow row(int step) {
            progress.step(step);
            return byOrder.get(step);
        }
    }

    private interface Mutation { void apply(Work work); }

    /**
     * Runs one change with the order's tracker row locked. When the change moves the order to another stage, its new
     * status is queued in the same transaction, versioned by the instance version every stage change increments, and
     * pushed to the catalogue once the change has committed; the relay trigger retries a push that fails.
     */
    private OrderWorkflowView mutate(String orderId, Mutation mutation) {
        boolean[] statusQueued = new boolean[1];
        Assembled assembled = tx.execute(status -> {
            Work work = new Work(access.clear(orderId, true, true));
            mutation.apply(work);
            TrackerRow fresh = repo.findByOrder(orderId, false).orElseThrow();
            Assembled after = assemble(work.role, work.userId, fresh);
            if (!work.progress.statusCode().equals(after.view().status())) {
                statuses.record(fresh.orderId(), fresh.schoolId(), after.view().status(), repo.instanceVersion(fresh.instanceId()));
                statusQueued[0] = true;
            }
            return after;
        });
        // After commit, with no lock held: a failed push never undoes the user's action and stays queued for the relay.
        if (statusQueued[0]) statuses.attempt(orderId);
        return finish(assembled);
    }

    // ==== assembling the view ============================================================================================

    /** The view built in the transaction, and what is needed to finish it once the transaction has committed. */
    private record Assembled(OrderWorkflowView view, WorkflowProgress progress, GateFacts activeFacts, OffsetDateTime lastCompletedAt,
                             OffsetDateTime currentDueAt, List<OpenCallback> callbacks, ZoneId zone, Instant now) {
        boolean needsDesignFiles() {
            return progress.current().map(step -> step.hasGate(GateType.DESIGN_FILES_PRESENT)).orElse(false);
        }
    }

    /**
     * After commit: the active stage's design-file gate is evaluated against the catalogue's count (the transaction
     * assumed none, so an unanswered catalogue leaves the conservative answer), then staff names are added.
     */
    private OrderWorkflowView finish(Assembled assembled) {
        OrderWorkflowView view = assembled.view();
        if (assembled.needsDesignFiles()) {
            try {
                int count = catalog.draftImages(view.orderId()).size();
                StepSpec active = assembled.progress().current().orElseThrow();
                GateFacts known = assembled.activeFacts();
                GateFacts facts = new GateFacts(known.data(), known.signedOff(), known.designState(), known.approvedCdrLink(),
                        known.currentCdrLink(), count, known.vendorSelections(), known.orderedCount(), known.categoryNames());
                List<GateFailure> unmet = Gates.unmet(active, facts);
                List<UpcomingAction> actions = UpcomingActions.derive(assembled.progress(), assembled.lastCompletedAt(),
                        assembled.currentDueAt(), unmet, assembled.callbacks(), assembled.now(), assembled.zone());
                view = view.withActiveStep(active.order(), unmet, actions);
            } catch (IllegalStateException unavailable) {
                LOG.warn("Design files of order {} not counted; the catalogue is unavailable: {}", view.orderId(), unavailable.toString());
            }
        }
        return view.withStaffNames(names.lookup(view.staffIds()));
    }

    private Assembled assemble(Role role, long userId, TrackerRow t) {
        boolean canAct = OrderWorkflowAccess.canAct();
        WorkflowTemplate template = template(t);
        List<StepRow> rows = repo.steps(t.instanceId());
        Map<Integer, StepRow> byOrder = byOrder(rows);
        WorkflowProgress progress = progress(template, t, rows);
        Map<Integer, Optional<Sla>> defaults = new HashMap<>();
        for (DefinitionStep step : repo.definitionSteps(t.definitionId())) defaults.put(step.stepOrder(), step.defaultSla());
        Map<Integer, Optional<Sla>> overrides = overrides(t.schoolId(), t.definitionId());
        List<NoteRow> notes = repo.notes(t.instanceId());
        OffsetDateTime now = now();
        ZoneId zone = zoneOrUtc(t.timeZone());
        String currentCdr = currentCdrLink(progress, byOrder);
        Optional<StepSpec> current = progress.current();
        GateFacts activeFacts = current.map(step -> facts(t, step, byOrder.get(step.order()).data(), currentCdr, 0)).orElse(null);
        List<GateFailure> activeUnmet = current.map(step -> Gates.unmet(step, activeFacts)).orElse(List.of());

        List<StepView> steps = new ArrayList<>();
        for (StepSpec spec : progress.path()) {
            StepRow row = byOrder.get(spec.order());
            StepStatus status = progress.status(spec.order());
            Optional<Sla> override = overrides.get(spec.order());
            EffectiveSla sla = override != null ? override.map(value -> effective(value, "SCHOOL")).orElse(null)
                    : defaults.getOrDefault(spec.order(), Optional.ofNullable(spec.defaultSla())).map(value -> effective(value, "DEFAULT")).orElse(null);
            boolean worker = role == Role.SUPERADMIN || Objects.equals(userId, row.assigneeUserId());
            StepCapabilities can = new StepCapabilities(
                    canAct && worker && progress.canEdit(role, spec.order()),
                    canAct && worker && progress.canComplete(role, spec.order()),
                    progress.canReopen(role, spec.order()),
                    progress.canSignOff(role, spec.order()),
                    canAct && progress.canAddNote(spec.order()));
            SignOffRecord signOff = StepDataRules.signOffOf(row.data())
                    .map(record -> new SignOffRecord(record.by(), null, OffsetDateTime.ofInstant(record.at(), ZoneOffset.UTC))).orElse(null);
            List<NoteEntry> stageNotes = notes.stream().filter(note -> Objects.equals(note.stepOrder(), spec.order()))
                    .map(OrderWorkflowService::noteEntry).toList();
            boolean active = status == StepStatus.ACTIVE;
            steps.add(new StepView(spec.order(), spec.code(), spec.title(), spec.phase().name(), spec.customisedOnly(), status.name(),
                    row.assigneeUserId(), null, row.startedAt(), row.dueAt(),
                    active && row.dueAt() != null && now.isAfter(row.dueAt()), sla, row.completedAt(), row.completedBy(), null,
                    StepDataRules.visible(row.data()), row.dataSavedAt(), row.dataSavedBy(), null, signOff, stageNotes,
                    active ? activeUnmet : List.of(), row.version(), can));
        }

        List<OpenCallback> open = callbacks.openCallbacks(t.instanceId());
        OffsetDateTime lastCompletedAt = progress.lastCompleted().map(step -> byOrder.get(step.order()).completedAt()).orElse(null);
        OffsetDateTime currentDueAt = current.map(step -> byOrder.get(step.order()).dueAt()).orElse(null);
        List<UpcomingAction> upcoming = UpcomingActions.derive(progress, lastCompletedAt, currentDueAt, activeUnmet, open, now.toInstant(), zone);

        Set<Long> selected = new LinkedHashSet<>();
        t.vendorSelections().values().forEach(selected::addAll);
        ResolvedGroups resolved = groups.resolve(t.schoolId(), t.categoryCode(), selected);
        DesignerRow designer = t.designerId() == null ? null : repo.designer(t.designerId()).orElse(null);
        DesignDecision design = new DesignDecision(t.designState(), t.designComment() != null ? t.designComment() : t.designStateNote(),
                t.designStateAt(), t.designStateBy(), null, t.approvedCdrLink());
        OrderContacts contacts = new OrderContacts(t.schoolContactName(), t.schoolContactPhone(), t.vendorName(), t.vendorPhone());
        List<NoteEntry> internalNotes = notes.stream().filter(note -> note.stepOrder() == null).map(OrderWorkflowService::noteEntry).toList();

        boolean superadmin = role == Role.SUPERADMIN;
        Long currentAssignee = current.map(step -> byOrder.get(step.order()).assigneeUserId()).orElse(null);
        // The order's point of contact (D37): the current stage's assignee, or the last stage's once every stage is done.
        Long pointOfContact = current.isPresent() ? currentAssignee
                : byOrder.get(progress.path().get(progress.total() - 1).order()).assigneeUserId();
        boolean currentWorker = superadmin || (canAct && current.isPresent() && Objects.equals(userId, currentAssignee));
        Optional<StepSpec> planning = progress.path().stream().filter(step -> step.hasBlock(BlockType.VENDOR_SELECTION)).findFirst();
        boolean canSelectVendors = planning.isPresent() && (superadmin || (canAct
                && progress.status(planning.get().order()) == StepStatus.ACTIVE
                && Objects.equals(userId, byOrder.get(planning.get().order()).assigneeUserId())));
        Optional<StepSpec> designStep = progress.designStep();
        boolean canManageDesignFiles = designStep.isPresent()
                && (superadmin || (canAct && Objects.equals(userId, byOrder.get(designStep.get().order()).assigneeUserId())))
                && progress.canManageDesignFiles(role, t.designState());
        Optional<StepSpec> approval = headApprovalStep(progress);
        boolean canApprove = superadmin && approval.isPresent() && progress.status(approval.get().order()) == StepStatus.ACTIVE
                && currentCdr != null;
        OrderCapabilities can = new OrderCapabilities(superadmin, role == Role.OPERATOR && canAct && unassigned(progress, rows),
                canSelectVendors, designStep.isPresent() && currentWorker, currentWorker, currentWorker, canAct, canAct,
                canManageDesignFiles, canApprove);

        Optional<StepSpec> currentStep = progress.current();
        WorkflowProgressView progressView = new WorkflowProgressView(progress.completedCount(), progress.total(),
                currentStep.map(StepSpec::order).orElse(null), currentStep.map(StepSpec::code).orElse(null),
                currentStep.map(StepSpec::title).orElse(null), progress.finished());
        OrderWorkflowView view = new OrderWorkflowView(t.orderId(), t.orderNumber(), t.definitionId(), t.categoryCode(), t.itemCode(),
                t.itemNoun(), t.schoolId(), t.schoolName(), t.customised(), t.orderedCount(), t.placedAt(), t.timeZone(),
                t.instanceStatus(), progress.statusCode(), progressView, pointOfContact, null, design, t.vendorSelections(), resolved,
                designer, contacts, internalNotes, upcoming, can, now, t.version(), steps);
        return new Assembled(view, progress, activeFacts, lastCompletedAt, currentDueAt, open, zone, now.toInstant());
    }

    private static NoteEntry noteEntry(NoteRow note) {
        return new NoteEntry(note.id(), note.stepOrder(), note.authorUserId(), null, note.body(), note.createdAt());
    }

    // ==== rules over the stored state ====================================================================================

    private WorkflowTemplate template(TrackerRow t) {
        return templates.find(t.definitionId())
                .orElseThrow(() -> new IllegalStateException("Order " + t.orderId() + " runs unknown template " + t.definitionId()));
    }

    /**
     * The order's progress over its template path, checked against its stage rows: the rows are exactly the path's stages,
     * in order, completed ones first. An order whose rows disagree is a data fault, not a client error.
     */
    private static WorkflowProgress progress(WorkflowTemplate template, TrackerRow t, List<StepRow> rows) {
        List<StepSpec> path = template.pathFor(t.customised());
        if (rows.size() != path.size()) throw new IllegalStateException("Order " + t.orderId() + "'s stages do not match its workflow path");
        int completed = 0;
        for (int index = 0; index < path.size(); index++) {
            StepRow row = rows.get(index);
            if (row.stepOrder() != path.get(index).order()) throw new IllegalStateException("Order " + t.orderId() + "'s stages are out of order");
            boolean done = "COMPLETED".equals(row.status());
            if (done && completed != index) throw new IllegalStateException("Order " + t.orderId() + " has a completed stage after an open one");
            if (done) completed++;
        }
        return WorkflowProgress.of(path, completed);
    }

    private static Map<Integer, StepRow> byOrder(List<StepRow> rows) {
        Map<Integer, StepRow> map = new HashMap<>();
        for (StepRow row : rows) map.put(row.stepOrder(), row);
        return map;
    }

    /**
     * What a stage's gates look at; the design-file count is the caller's (asked of the catalogue outside any lock). A vendor
     * gate that names its categories names them as the directory does now, since superadmin may rename one (D31).
     */
    private GateFacts facts(TrackerRow t, StepSpec step, Map<String, Object> data, String currentCdrLink, int designFiles) {
        boolean namesCategories = step.gates().stream()
                .anyMatch(gate -> gate.type() == GateType.VENDORS_SELECTED && gate.categories() != null && !gate.categories().isEmpty());
        return new GateFacts(data, step.hasSignOff() && StepDataRules.signedOff(data), t.designState(), t.approvedCdrLink(),
                currentCdrLink, designFiles, t.vendorSelections(), t.orderedCount(), namesCategories ? vendors.categoryNames() : Map.of());
    }

    /** The CDR link saved now: the first stage on the path that declares the CDR link field. */
    private static String currentCdrLink(WorkflowProgress progress, Map<Integer, StepRow> byOrder) {
        for (StepSpec step : progress.path()) {
            if (step.fields().containsKey(CDR_LINK_FIELD)) return StepDataRules.text(byOrder.get(step.order()).data(), CDR_LINK_FIELD);
        }
        return null;
    }

    /** The stage Head Approval is decided on: the one gated on {@code HEAD_APPROVED}. */
    private static Optional<StepSpec> headApprovalStep(WorkflowProgress progress) {
        return progress.path().stream().filter(step -> step.hasGate(GateType.HEAD_APPROVED)).findFirst();
    }

    /** The stage an order-level change is recorded on: the active one, or the last once every stage is done. */
    private static int currentOrLast(Work work) {
        return work.progress.current().orElse(work.progress.path().get(work.progress.total() - 1)).order();
    }

    /** Operators work only the stages assigned to them; superadmin works any stage. */
    private static void requireWorker(Work work, StepRow row, StepSpec step) {
        if (work.role == Role.OPERATOR) requireAssignee(work, row, step);
    }

    private static void requireAssignee(Work work, StepRow row, StepSpec step) {
        if (!Objects.equals(work.userId, row.assigneeUserId())) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.NOT_ASSIGNEE, step.title() + " is assigned to another operator");
        }
    }

    /** Superadmin, or the operator assigned the order's current stage. */
    private static void requireCurrentWorker(Work work, String action) {
        if (work.role == Role.SUPERADMIN) return;
        Optional<StepSpec> current = work.progress.current();
        if (current.isEmpty() || !Objects.equals(work.userId, work.byOrder.get(current.get().order()).assigneeUserId())) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.NOT_ASSIGNEE,
                    "Only the operator assigned the current stage, or a superadmin, can " + action);
        }
    }

    private static void requireSuperadmin(Role role, String message) {
        if (role != Role.SUPERADMIN) throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, message);
    }

    private static void requireVersion(StepRow row, long expectedVersion) {
        if (row.version() != expectedVersion) throw new WorkflowRuleViolation(VERSION_CONFLICT, STAGE_CHANGED);
    }

    private static void requireSaved(int updated) {
        if (updated == 0) throw new WorkflowRuleViolation(VERSION_CONFLICT, STAGE_CHANGED);
    }

    /** The keys whose values differ between two saved data maps, in the order they first appear. */
    private static List<String> changedKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>(after.keySet());
        keys.addAll(before.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> left = before.containsKey(key) ? Map.of(key, before.get(key)) : Map.of();
            Map<String, Object> right = after.containsKey(key) ? Map.of(key, after.get(key)) : Map.of();
            if (!StepDataRules.sameValues(left, right)) changed.add(key);
        }
        return changed;
    }

    /** An action's facts as the JSON object the activity history reads (design D40). */
    private String facts(Map<String, ?> facts) {
        return json.writeValueAsString(facts);
    }

    // ==== time slots =====================================================================================================

    private Map<Integer, Optional<Sla>> overrides(long schoolId, String definitionId) {
        Map<Integer, Optional<Sla>> map = new HashMap<>();
        repo.schoolSla(schoolId, definitionId).forEach(override -> map.put(override.stepOrder(), override.sla()));
        return map;
    }

    /** A school override when present (even "none"), otherwise the template step's current default. */
    private Map<Integer, Optional<Sla>> effectiveSla(long schoolId, String definitionId) {
        Map<Integer, Optional<Sla>> overrides = overrides(schoolId, definitionId);
        Map<Integer, Optional<Sla>> effective = new HashMap<>();
        for (DefinitionStep step : repo.definitionSteps(definitionId)) {
            effective.put(step.stepOrder(), overrides.containsKey(step.stepOrder()) ? overrides.get(step.stepOrder()) : step.defaultSla());
        }
        return effective;
    }

    private Optional<Sla> slaOf(Work work, int step) {
        return effectiveSla(work.t.schoolId(), work.t.definitionId()).getOrDefault(step, Optional.empty());
    }

    private static EffectiveSla effective(Sla sla, String source) {
        return new EffectiveSla(sla.value(), sla.unit().name(), source);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    private static OffsetDateTime due(OffsetDateTime start, Optional<Sla> sla, ZoneId zone) {
        return sla == null || sla.isEmpty() ? null : OffsetDateTime.ofInstant(sla.get().deadline(start.toInstant(), zone), zone);
    }

    private static ZoneId zone(TrackerRow t) {
        return zoneOrUtc(t.timeZone());
    }

    private static ZoneId zoneOrUtc(String id) {
        try {
            return ZoneId.of(id == null ? "" : id.strip());
        } catch (DateTimeException invalid) {
            return ZoneOffset.UTC;
        }
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null ? "" : id.strip());
        } catch (DateTimeException invalid) {
            throw bad("timeZone must be an IANA zone such as Asia/Kolkata");
        }
    }

    // ==== input ==========================================================================================================

    private static String text(String value, int max, String what) {
        if (blank(value)) return null;
        String clean = value.strip();
        if (clean.length() > max) throw bad(what + " is limited to " + max + " characters");
        return clean;
    }

    private static String phone(String value, String what) {
        if (blank(value)) return null;
        String clean = value.strip();
        if (!PHONE.matcher(clean).matches() || clean.chars().noneMatch(Character::isDigit)) {
            throw bad(what + " must be a phone number (digits, spaces, +, -, brackets; 32 characters at most)");
        }
        return clean;
    }

    private static String optionalPattern(String value, Pattern pattern, String message) {
        if (blank(value)) return null;
        String clean = value.strip();
        if (!pattern.matcher(clean).matches()) throw bad(message);
        return clean;
    }

    private static String optionalIn(String value, Collection<String> allowed, String message) {
        if (blank(value)) return null;
        String clean = value.strip();
        if (!allowed.contains(clean)) throw bad(message);
        return clean;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
