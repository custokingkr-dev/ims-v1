package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.tracker.Counts;
import com.custoking.ims.operationsservice.application.tracker.NoteRules;
import com.custoking.ims.operationsservice.application.tracker.Role;
import com.custoking.ims.operationsservice.application.tracker.Sla;
import com.custoking.ims.operationsservice.application.tracker.StepData;
import com.custoking.ims.operationsservice.application.tracker.StepFacts;
import com.custoking.ims.operationsservice.application.tracker.StepStatus;
import com.custoking.ims.operationsservice.application.tracker.TrackerProgress;
import com.custoking.ims.operationsservice.application.tracker.TrackerStep;
import com.custoking.ims.operationsservice.application.tracker.TrackerView;
import com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway;
import com.custoking.ims.operationsservice.infrastructure.OperatorEligibility;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository.DefinitionStep;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository.StepRow;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository.TrackerRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the notebook order tracker (spec v4): the pure rules in {@code application.tracker} decide what is
 * allowed, this class loads and saves the state inside one transaction per call, with the order's tracker row locked
 * so two people cannot move the same order at once.
 */
@Service
public class NotebookTrackerService {
    private final NotebookTrackerRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OperatorEligibility eligibility;
    private final CatalogOrderGateway catalog;

    private static final Logger LOG = LoggerFactory.getLogger(NotebookTrackerService.class);

    public NotebookTrackerService(NotebookTrackerRepository repo, TransactionTemplate tx, Clock clock,
                                  OperatorEligibility eligibility, CatalogOrderGateway catalog) {
        this.repo = repo;
        this.tx = tx;
        this.clock = clock;
        this.eligibility = eligibility;
        this.catalog = catalog;
    }

    /** Who is acting, reduced to the two roles the tracker knows. */
    public record Actor(Role role, long userId) { }

    public record CreateTracker(String orderId, long schoolId, boolean customised, int orderedCount,
                                OffsetDateTime placedAt, String timeZone, Long placedBy, Long assigneeUserId, String schoolName) {
        public CreateTracker(String orderId, long schoolId, boolean customised, int orderedCount,
                             OffsetDateTime placedAt, String timeZone, Long placedBy, Long assigneeUserId) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, null);
        }
    }

    public record Created(long instanceId, boolean created) { }

    // ---- creation (machine call from school-core at order placement) ----------------------------------------------

    /** Idempotent: a second call for the same order returns the existing tracker. */
    public Created create(CreateTracker cmd) {
        if (cmd.orderId() == null || cmd.orderId().isBlank() || cmd.orderId().length() > 255) throw bad("orderId is required (255 characters at most)");
        if (cmd.schoolId() <= 0) throw bad("A positive schoolId is required");
        if (cmd.orderedCount() <= 0) throw bad("orderedCount must be positive");
        if (cmd.placedAt() == null) throw bad("placedAt is required");
        if (cmd.schoolName() != null && cmd.schoolName().strip().length() > 200) throw bad("schoolName is limited to 200 characters");
        ZoneId zone = zone(cmd.timeZone());
        try {
            return createInTransaction(cmd, zone);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // Order ids are globally unique; an order already tracked under another school is not visible to this scope.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is already tracked");
        }
    }

    private Created createInTransaction(CreateTracker cmd, ZoneId zone) {
        return tx.execute(status -> {
            repo.applySchoolScope(cmd.schoolId());
            Optional<TrackerRow> existing = repo.findByOrder(cmd.orderId(), true);
            if (existing.isPresent()) {
                if (existing.get().schoolId() != cmd.schoolId()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order belongs to another school");
                return new Created(existing.get().instanceId(), false);
            }
            List<TrackerStep> path = TrackerStep.pathFor(cmd.customised());
            long instanceId = repo.insertInstance(cmd.schoolId(), cmd.orderId(), path.get(0).number(), cmd.placedBy());
            repo.insertTracker(instanceId, cmd.schoolId(), cmd.orderId(), cmd.customised(), cmd.orderedCount(), cmd.placedAt(), zone.getId(),
                    cmd.schoolName() == null || cmd.schoolName().isBlank() ? null : cmd.schoolName().strip());
            Map<Integer, Optional<Sla>> slas = effectiveSla(cmd.schoolId());
            for (int i = 0; i < path.size(); i++) {
                TrackerStep step = path.get(i);
                boolean first = i == 0;
                repo.insertStep(instanceId, cmd.schoolId(), step.number(), first ? "ACTIVE" : "LOCKED", cmd.assigneeUserId(),
                        first ? cmd.placedAt() : null, first ? due(cmd.placedAt(), slas.get(step.number()), zone) : null);
            }
            repo.recordAction(instanceId, cmd.schoolId(), path.get(0).number(), "TRACKER_CREATED", null);
            return new Created(instanceId, true);
        });
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    /**
     * The worklist. {@code mine} lists orders whose active step is assigned to the caller; otherwise every order in the
     * caller's scope (an operator's schools and items, or everything for superadmin). Soonest deadline first.
     */
    public List<TrackerView.ListItem> list(boolean mine, Long schoolId, boolean includeFinished, int limit) {
        return tx.execute(status -> {
            begin();
            Actor actor = actor(false);
            if (schoolId != null) requireSchoolAccess(schoolId);
            // Row-level security also admits the request's school id, so bound an operator to their own schools here too.
            Set<Long> schools = actor.role() == Role.SUPERADMIN ? null : accessibleSchools();
            OffsetDateTime now = now();
            return repo.list(schoolId, schools, mine ? actor.userId() : null, includeFinished, limit).stream().map(row -> {
                TrackerView.Progress progress = new TrackerView.Progress(row.completed(), row.total(), row.activeStep(),
                        row.activeStep() == null ? null : TrackerStep.ofNumber(row.activeStep()).map(TrackerStep::title).orElse(null),
                        row.activeStep() == null);
                return new TrackerView.ListItem(row.orderId(), row.schoolId(), row.schoolName(), row.customised(), row.orderedCount(),
                        row.placedAt(), statusOf(progress), row.activeStep(), progress.currentTitle(), row.assigneeUserId(), row.dueAt(),
                        row.activeStep() != null && row.dueAt() != null && now.isAfter(row.dueAt()), row.completed(), row.total());
            }).toList();
        });
    }

    public TrackerView view(String orderId) {
        return tx.execute(status -> {
            begin();
            Actor actor = actor(false);
            return assemble(actor, tracker(orderId, false));
        });
    }

    // ---- step work ----------------------------------------------------------------------------------------------------

    public TrackerView saveDraft(String orderId, int stepNumber, Map<String, Object> data, long expectedVersion) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            TrackerStep step = stepOnPath(progress, stepNumber);
            StepRow row = row(rows, step);
            requireWorker(actor, row);
            requireEditable(progress.canEditDetails(actor.role(), step), progress, step, actor);
            Map<String, Object> clean = StepData.validate(step, data);
            // The sign-off record is written only by its own action, so a draft save must not wipe it.
            if (row.data().containsKey(StepData.SIGN_OFF_KEY)) clean.put(StepData.SIGN_OFF_KEY, row.data().get(StepData.SIGN_OFF_KEY));
            requireSaved(repo.saveStepData(t.instanceId(), step.number(), clean, actor.userId(), expectedVersion));
        });
    }

    public TrackerView saveStageNote(String orderId, int stepNumber, String note, long expectedVersion) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            TrackerStep step = stepOnPath(progress, stepNumber);
            StepRow row = row(rows, step);
            requireWorker(actor, row);
            if (!progress.canEditStageNote(actor.role(), step)) {
                throw forbidden(actor.role() == Role.SUPERADMIN
                        ? "Superadmin cannot edit stage notes"
                        : "Stage notes can be edited only while the step is active");
            }
            NoteRules.requireWithin(note, NoteRules.STAGE_NOTE_MAX_WORDS, "A stage note");
            requireSaved(repo.saveStageNote(t.instanceId(), step.number(), note == null || note.isBlank() ? null : note.strip(),
                    actor.userId(), expectedVersion));
        });
    }

    public TrackerView completeStep(String orderId, int stepNumber, long expectedVersion) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            TrackerStep step = stepOnPath(progress, stepNumber);
            StepRow row = row(rows, step);
            requireWorker(actor, row);
            TrackerProgress next = progress.complete(step, actor.role(), facts(t, rows, step));
            requireSaved(repo.completeStep(t.instanceId(), step.number(), actor.userId(), expectedVersion));
            ZoneId zone = zone(t.timeZone());
            OffsetDateTime now = now();
            Optional<TrackerStep> following = next.current();
            if (following.isPresent()) {
                Optional<Sla> sla = effectiveSla(t.schoolId()).getOrDefault(following.get().number(), Optional.empty());
                repo.activateStep(t.instanceId(), following.get().number(), now, due(now, sla, zone));
                repo.setInstanceProgress(t.instanceId(), following.get().number(), "IN_PROGRESS", false);
            } else {
                repo.setInstanceProgress(t.instanceId(), step.number(), "COMPLETED", true);
            }
            repo.recordAction(t.instanceId(), t.schoolId(), step.number(), "STEP_COMPLETED", null);
        });
    }

    /** Superadmin only. The reopened step restarts its clock; later steps revert to not-completed with data kept. */
    public TrackerView reopenStep(String orderId, int stepNumber) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            TrackerStep step = stepOnPath(progress, stepNumber);
            progress.reopen(step, actor.role());
            repo.lockStepsAfter(t.instanceId(), step.number());
            OffsetDateTime now = now();
            Optional<Sla> sla = effectiveSla(t.schoolId()).getOrDefault(step.number(), Optional.empty());
            repo.activateStep(t.instanceId(), step.number(), now, due(now, sla, zone(t.timeZone())));
            repo.setInstanceProgress(t.instanceId(), step.number(), "IN_PROGRESS", false);
            repo.recordAction(t.instanceId(), t.schoolId(), step.number(), "STEP_REOPENED", null);
        });
    }

    /** Superadmin sign-off for the quality gate (step 8) and the executive consignment sign-off (step 12). */
    public TrackerView signOff(String orderId, int stepNumber) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            TrackerStep step = stepOnPath(progress, stepNumber);
            if (step != TrackerStep.PRE_PRINT_QUALITY_GATE && step != TrackerStep.DELIVERY_INVOICING) {
                throw bad(step.title() + " has no sign-off");
            }
            requireSuperadmin(actor, "Only superadmin can sign off");
            requireEditable(progress.canEditDetails(actor.role(), step), progress, step, actor);
            repo.mergeStepData(t.instanceId(), step.number(), Map.of(StepData.SIGN_OFF_KEY, StepData.signOff(actor.userId(), now().toInstant())), actor.userId());
            repo.recordAction(t.instanceId(), t.schoolId(), step.number(), "STEP_SIGNED_OFF", null);
        });
    }

    public TrackerView addFollowUp(String orderId, int percentComplete, LocalDate nextOn, String contactPhone) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            StepRow row = row(rows, TrackerStep.PRODUCTION_FOLLOW_UP);
            requireWorker(actor, row);
            requireEditable(progress.canEditDetails(actor.role(), TrackerStep.PRODUCTION_FOLLOW_UP), progress, TrackerStep.PRODUCTION_FOLLOW_UP, actor);
            if (percentComplete < 0 || percentComplete > 100) throw bad("percentComplete must be between 0 and 100");
            String phone = contactPhone == null || contactPhone.isBlank() ? null : contactPhone.strip();
            if (phone != null && phone.length() > 32) throw bad("contactPhone is limited to 32 characters");
            repo.addFollowUp(t.instanceId(), t.schoolId(), percentComplete, nextOn, phone, actor.userId());
        });
    }

    // ---- design approval (steps 2 and 3) ----------------------------------------------------------------------------

    /** The operator (or superadmin) sends the drafts for approval; allowed in step 2 and again after changes were requested. */
    public TrackerView sendDesign(String orderId) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireCustomised(t);
            StepRow designing = row(rows, TrackerStep.DESIGNING_ACTIVITY);
            requireWorker(actor, designing);
            boolean changesRequested = "CHANGES_REQUESTED".equals(t.designState());
            if (!progress.canManageDraftImages(actor.role(), changesRequested)) throw conflict("Step 2 is not open for sending a design");
            if (!"NOT_SENT".equals(t.designState()) && !changesRequested) throw conflict("The design has already been sent for approval");
            requireDraftImages(t.orderId(), null);
            repo.setDesignState(t.instanceId(), "SENT", null, actor.userId(), null);
            repo.recordAction(t.instanceId(), t.schoolId(), TrackerStep.DESIGNING_ACTIVITY.number(), "DESIGN_SENT", null);
        });
    }

    /** Superadmin approves a draft; still allowed while a change request is pending. */
    public TrackerView approveDesign(String orderId, long draftRef, String comment) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireCustomised(t);
            requireSuperadmin(actor, "Only superadmin can approve a design");
            requireHeadApprovalOpen(progress, t);
            if (draftRef <= 0) throw bad("draftRef is required");
            String note = optionalComment(comment);
            requireDraftImages(t.orderId(), draftRef);
            repo.setDesignState(t.instanceId(), "APPROVED", note, actor.userId(), draftRef);
            repo.recordAction(t.instanceId(), t.schoolId(), TrackerStep.HEAD_APPROVAL.number(), "DESIGN_APPROVED", note);
        });
    }

    public TrackerView requestChanges(String orderId, String comment) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireCustomised(t);
            requireSuperadmin(actor, "Only superadmin can request changes");
            requireHeadApprovalOpen(progress, t);
            String note = optionalComment(comment);
            repo.setDesignState(t.instanceId(), "CHANGES_REQUESTED", note, actor.userId(), null);
            repo.recordAction(t.instanceId(), t.schoolId(), TrackerStep.HEAD_APPROVAL.number(), "CHANGES_REQUESTED", note);
        });
    }

    // ---- assignment (superadmin only, to eligible operators only) ------------------------------------------------------

    /** Reassigns one step that is not yet completed. */
    public TrackerView assignStep(String orderId, int stepNumber, long userId) {
        requireEligibleOperator(orderId, userId);
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireSuperadmin(actor, "Only superadmin can assign work");
            TrackerStep step = stepOnPath(progress, stepNumber);
            if (progress.status(step) == StepStatus.COMPLETED) throw conflict(step.title() + " is completed and cannot be reassigned");
            repo.assignStep(t.instanceId(), step.number(), userId, actor.userId());
            repo.recordAction(t.instanceId(), t.schoolId(), step.number(), "STEP_ASSIGNED", "operator " + userId);
        });
    }

    /** Assigns every step that is not yet completed; used for a new order that no operator was picked for. */
    public TrackerView assignOrder(String orderId, long userId) {
        requireEligibleOperator(orderId, userId);
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireSuperadmin(actor, "Only superadmin can assign work");
            int first = -1;
            for (StepRow row : rows) {
                if ("COMPLETED".equals(row.status())) continue;
                if (first < 0) first = row.stepOrder();
                repo.assignStep(t.instanceId(), row.stepOrder(), userId, actor.userId());
            }
            if (first < 0) throw conflict("Every step is completed");
            repo.recordAction(t.instanceId(), t.schoolId(), first, "STEP_ASSIGNED", "all open steps to operator " + userId);
        });
    }

    /**
     * Identity decides who is an eligible operator. It is asked before the transaction opens so a slow peer never holds a
     * row lock, and a peer that cannot answer refuses the assignment instead of letting it through.
     */
    private void requireEligibleOperator(String orderId, long userId) {
        if (userId <= 0) throw bad("userId is required");
        long schoolId = tx.execute(status -> {
            begin();
            requireSuperadmin(actor(true), "Only superadmin can assign work");
            return tracker(orderId, false).schoolId();
        });
        boolean eligible;
        try {
            eligible = eligibility.eligible(userId, schoolId, NotebookTrackerRepository.CATALOG_CATEGORY);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operator directory is unavailable; try again");
        }
        if (!eligible) throw bad("User is not an eligible operator for this school and catalogue item");
    }

    // ---- people ---------------------------------------------------------------------------------------------------------

    public TrackerView setVendor(String orderId, String name, String phone) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireSuperadmin(actor, "Only superadmin can set the vendor");
            String cleanName = name == null ? "" : name.strip();
            String cleanPhone = phone == null ? "" : phone.strip();
            if (cleanName.isEmpty() || cleanName.length() > 200) throw bad("Vendor name is required (200 characters at most)");
            if (cleanPhone.isEmpty() || cleanPhone.length() > 32) throw bad("Vendor phone is required (32 characters at most)");
            repo.setVendor(t.instanceId(), cleanName, cleanPhone);
        });
    }

    public TrackerView setDesigner(String orderId, long designerId) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            requireSuperadmin(actor, "Only superadmin can choose the designer");
            requireCustomised(t);
            repo.designer(designerId).filter(NotebookTrackerRepository.DesignerRow::active)
                    .orElseThrow(() -> bad("Designer not found or inactive"));
            repo.setDesigner(t.instanceId(), designerId);
        });
    }

    public TrackerView addInternalNote(String orderId, String body) {
        return mutate(orderId, (actor, t, progress, rows) -> {
            if (body == null || body.isBlank()) throw bad("A note cannot be empty");
            NoteRules.requireWithin(body, NoteRules.INTERNAL_NOTE_MAX_WORDS, "An internal note");
            if (body.length() > 4000) throw bad("An internal note is limited to 4000 characters");
            repo.addInternalNote(t.instanceId(), t.schoolId(), actor.userId(), body.strip());
        });
    }

    // ---- designers (global, superadmin-maintained) and time slots ----------------------------------------------------

    public List<NotebookTrackerRepository.DesignerRow> designers() {
        return tx.execute(status -> {
            begin();
            actor(false);
            return repo.designers(!TenantContext.get().isSuperAdmin());
        });
    }

    public NotebookTrackerRepository.DesignerRow createDesigner(String name, String phone) {
        return tx.execute(status -> {
            begin();
            Actor actor = actor(true);
            requireSuperadmin(actor, "Only superadmin can maintain designers");
            String n = name == null ? "" : name.strip(), p = phone == null ? "" : phone.strip();
            if (n.isEmpty() || n.length() > 200 || p.isEmpty() || p.length() > 32) throw bad("Designer name (200) and phone (32) are required");
            long id;
            try {
                id = repo.insertDesigner(n, p, actor.userId());
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw conflict("A designer with this name and phone already exists");
            }
            return repo.designer(id).orElseThrow();
        });
    }

    public NotebookTrackerRepository.DesignerRow updateDesigner(long id, String name, String phone, boolean active) {
        return tx.execute(status -> {
            begin();
            Actor actor = actor(true);
            requireSuperadmin(actor, "Only superadmin can maintain designers");
            String n = name == null ? "" : name.strip(), p = phone == null ? "" : phone.strip();
            if (n.isEmpty() || n.length() > 200 || p.isEmpty() || p.length() > 32) throw bad("Designer name (200) and phone (32) are required");
            int updated;
            try {
                updated = repo.updateDesigner(id, n, p, active, actor.userId());
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw conflict("A designer with this name and phone already exists");
            }
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Designer not found");
            return repo.designer(id).orElseThrow();
        });
    }

    public record SlaRow(int step, String title, TrackerView.Sla sla) { }

    /** The effective time slot per step for a school: its override if set, else the definition default. */
    public List<SlaRow> timeSlots(long schoolId) {
        return tx.execute(status -> {
            begin();
            actor(false);
            requireSchoolAccess(schoolId);
            Map<Integer, Optional<Sla>> overrides = overrides(schoolId);
            return repo.definitionSteps().stream().map(def -> {
                Optional<Sla> override = overrides.get(def.stepOrder());
                if (override != null) return new SlaRow(def.stepOrder(), def.name(), override.map(s -> view(s, "SCHOOL")).orElse(null));
                return new SlaRow(def.stepOrder(), def.name(), def.defaultSla() == null ? null : view(def.defaultSla(), "DEFAULT"));
            }).toList();
        });
    }

    /** Superadmin only. A null slot stores "no timer for this school and step". */
    public void setTimeSlot(long schoolId, int stepOrder, Sla sla) {
        tx.executeWithoutResult(status -> {
            begin();
            Actor actor = actor(true);
            requireSuperadmin(actor, "Only superadmin can change time slots");
            if (schoolId <= 0) throw bad("A positive schoolId is required");
            if (repo.definitionSteps().stream().noneMatch(s -> s.stepOrder() == stepOrder)) throw bad("Unknown step " + stepOrder);
            if (sla != null && sla.value() > 10_000) throw bad("A time slot is limited to 10000");
            repo.upsertSchoolSla(schoolId, stepOrder, sla, actor.userId());
        });
    }

    /** Superadmin only: removes the school override so the definition default applies again. */
    public void clearTimeSlot(long schoolId, int stepOrder) {
        tx.executeWithoutResult(status -> {
            begin();
            requireSuperadmin(actor(true), "Only superadmin can change time slots");
            repo.deleteSchoolSla(schoolId, stepOrder);
        });
    }

    // ---- internals ----------------------------------------------------------------------------------------------------------

    private interface Mutation { void apply(Actor actor, TrackerRow tracker, TrackerProgress progress, List<StepRow> rows); }

    private TrackerView mutate(String orderId, Mutation mutation) {
        String[] newStatus = new String[1];
        TrackerView view = tx.execute(status -> {
            begin();
            Actor actor = actor(true);
            TrackerRow t = tracker(orderId, true);
            List<StepRow> rows = repo.steps(t.instanceId());
            TrackerProgress progress = progress(t, rows);
            mutation.apply(actor, t, progress, rows);
            TrackerRow fresh = tracker(orderId, false);
            TrackerView assembled = assemble(actor, fresh);
            Integer before = progress.current().map(TrackerStep::number).orElse(null);
            if (!Objects.equals(before, assembled.progress().currentStep()) || progress.finished() != assembled.progress().finished()) {
                newStatus[0] = statusOf(assembled.progress());
            }
            return assembled;
        });
        if (newStatus[0] != null) pushStatus(orderId, newStatus[0]);
        return view;
    }

    /** The order's status is the current step's code, or COMPLETED once every step is. */
    static String statusOf(TrackerView.Progress progress) {
        return progress.finished() ? "COMPLETED" : TrackerStep.ofNumber(progress.currentStep()).orElseThrow().name();
    }

    /**
     * Shown on the order after the change is committed. A failure is logged and does not undo the user's action; the
     * status is pushed again the next time the step changes.
     */
    private void pushStatus(String orderId, String status) {
        try {
            catalog.pushStatus(orderId, status);
        } catch (IllegalStateException unavailable) {
            LOG.warn("Order {} moved to {} but its status could not be shown: {}", orderId, status, unavailable.getMessage());
        }
    }

    /** A design is only sent or approved against drafts that exist; the catalogue owns the images. */
    private void requireDraftImages(String orderId, Long draftRef) {
        List<CatalogOrderGateway.DraftImage> drafts;
        try {
            drafts = catalog.draftImages(orderId);
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The order catalogue is unavailable; try again");
        }
        if (drafts.isEmpty()) throw bad("Upload at least one design draft first");
        if (draftRef != null && drafts.stream().noneMatch(draft -> draft.id() == draftRef)) {
            throw bad("That draft is not a current draft of this order");
        }
    }

    // ---- image access (machine call from school-core) ------------------------------------------------------------------

    public record ImageAccessResult(boolean allowed, String reason) { }

    /**
     * Whether this user may upload or delete design drafts now. school-core asks before every draft upload or delete and
     * has already checked the caller's school and catalogue scope; the tracker decides by step and assignment.
     */
    public ImageAccessResult designImageAccess(String orderId, long userId, String role) {
        return tx.execute(status -> {
            repo.applyBypassScope();
            Optional<TrackerRow> found = repo.findByOrder(orderId, false);
            if (found.isEmpty()) return new ImageAccessResult(false, "This order has no tracker yet");
            TrackerRow t = found.get();
            if (!t.customised()) return new ImageAccessResult(false, "Design drafts do not apply to a wholesale order");
            Role actorRole = "SUPERADMIN".equalsIgnoreCase(role) ? Role.SUPERADMIN : "OPERATIONS".equalsIgnoreCase(role) ? Role.OPERATOR : null;
            if (actorRole == null) return new ImageAccessResult(false, "Design drafts are managed by superadmin and operators");
            List<StepRow> rows = repo.steps(t.instanceId());
            TrackerProgress progress = progress(t, rows);
            if (actorRole == Role.OPERATOR && !Objects.equals(userId, row(rows, TrackerStep.DESIGNING_ACTIVITY).assigneeUserId())) {
                return new ImageAccessResult(false, "This step is assigned to another operator");
            }
            if (!progress.canManageDraftImages(actorRole, "CHANGES_REQUESTED".equals(t.designState()))) {
                return new ImageAccessResult(false, "Design drafts cannot be changed in the current step");
            }
            return new ImageAccessResult(true, null);
        });
    }

    private void begin() {
        repo.applyOperatorScope();
    }

    private Actor actor(boolean write) {
        TenantContext ctx = TenantContext.get();
        if (ctx.userId() == null) throw forbidden("Authenticated user required");
        if (ctx.isSuperAdmin()) return new Actor(Role.SUPERADMIN, ctx.userId());
        if (!ctx.isOperations()) throw forbidden("The notebook order tracker is for superadmin and operators only");
        TenantScope.requirePermission(write ? "notebook-tracker:act" : "notebook-tracker:read");
        return new Actor(Role.OPERATOR, ctx.userId());
    }

    /**
     * One order's tracker, which the caller must be allowed to see. Row-level security already hides other schools, but it
     * also admits the request's school id, which can be a school the operator no longer works; this check does not.
     */
    private TrackerRow tracker(String orderId, boolean lock) {
        TrackerRow t = repo.findByOrder(orderId, lock).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracker not found"));
        requireSchoolAccess(t.schoolId());
        return t;
    }

    private TrackerProgress progress(TrackerRow t, List<StepRow> rows) {
        List<TrackerStep> path = TrackerStep.pathFor(t.customised());
        if (rows.size() != path.size()) throw new IllegalStateException("Tracker steps do not match the order's path");
        int completed = 0;
        for (int i = 0; i < path.size(); i++) {
            StepRow row = rows.get(i);
            if (row.stepOrder() != path.get(i).number()) throw new IllegalStateException("Tracker steps are out of order");
            boolean done = "COMPLETED".equals(row.status());
            if (done && completed != i) throw new IllegalStateException("A completed step follows an incomplete one");
            if (done) completed++;
        }
        return TrackerProgress.restore(t.customised(), completed);
    }

    /** Gate facts for completing {@code step}; a sign-off counts only on the step being completed. */
    private StepFacts facts(TrackerRow t, List<StepRow> rows, TrackerStep step) {
        Map<String, Object> counts = row(rows, TrackerStep.VALIDATION_COUNT).data();
        Counts typed = new Counts(t.orderedCount(), intOf(counts.get("producedCount")), intOf(counts.get("acceptedCount")), intOf(counts.get("dispatchCount")));
        Object note = counts.get("discrepancyNote");
        return new StepFacts(!"NOT_SENT".equals(t.designState()), "APPROVED".equals(t.designState()),
                signedOff(rows, step), typed, note == null ? null : String.valueOf(note));
    }

    private boolean signedOff(List<StepRow> rows, TrackerStep step) {
        return rows.stream().filter(r -> r.stepOrder() == step.number()).anyMatch(r -> r.data().containsKey(StepData.SIGN_OFF_KEY));
    }

    private static Integer intOf(Object value) { return value instanceof Number n ? n.intValue() : null; }

    private TrackerStep stepOnPath(TrackerProgress progress, int number) {
        TrackerStep step = TrackerStep.ofNumber(number).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown step " + number));
        if (!progress.path().contains(step)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, step.title() + " does not apply to this order");
        return step;
    }

    private static StepRow row(List<StepRow> rows, TrackerStep step) {
        return rows.stream().filter(r -> r.stepOrder() == step.number()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, step.title() + " does not apply to this order"));
    }

    /** Operators work only the steps assigned to them; superadmin works any step. */
    private void requireWorker(Actor actor, StepRow row) {
        if (actor.role() == Role.OPERATOR && !Objects.equals(actor.userId(), row.assigneeUserId())) {
            throw forbidden("This step is assigned to another operator");
        }
    }

    private void requireEditable(boolean allowed, TrackerProgress progress, TrackerStep step, Actor actor) {
        if (allowed) return;
        StepStatus status = progress.status(step);
        if (status == StepStatus.LOCKED) throw conflict(step.title() + " is locked until the previous step is completed");
        if (status == StepStatus.COMPLETED) throw forbidden(step.title() + " is completed; only superadmin can edit it");
        throw forbidden(step.title() + " is for superadmin only");
    }

    /**
     * Superadmin sees every school; an operator only the schools where they are school-wide or scoped to notebooks. Used by
     * every read and write of a tracker, the worklist and the time slots.
     */
    private void requireSchoolAccess(long schoolId) {
        if (!TenantContext.get().isSuperAdmin() && !accessibleSchools().contains(schoolId)) {
            throw forbidden("No access to this school");
        }
    }

    private static Set<Long> accessibleSchools() {
        return TenantContext.get().operatorSchoolsFor(NotebookTrackerRepository.CATALOG_CATEGORY);
    }

    private void requireSuperadmin(Actor actor, String message) {
        if (actor.role() != Role.SUPERADMIN) throw forbidden(message);
    }

    private void requireCustomised(TrackerRow t) {
        if (!t.customised()) throw bad("Design steps do not apply to a wholesale order");
    }

    private void requireHeadApprovalOpen(TrackerProgress progress, TrackerRow t) {
        if (progress.status(TrackerStep.HEAD_APPROVAL) != StepStatus.ACTIVE) throw conflict("Head Approval is not the active step");
        if ("NOT_SENT".equals(t.designState())) throw conflict("No design has been sent for approval");
    }

    private static String optionalComment(String comment) {
        if (comment == null || comment.isBlank()) return null;
        if (comment.strip().length() > 1000) throw bad("A comment is limited to 1000 characters");
        return comment.strip();
    }

    private static void requireSaved(int updated) {
        if (updated == 0) throw conflict("This step changed since you loaded it; reload and try again");
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    private static OffsetDateTime due(OffsetDateTime start, Optional<Sla> sla, ZoneId zone) {
        return sla == null || sla.isEmpty() ? null : OffsetDateTime.ofInstant(sla.get().deadline(start.toInstant(), zone), zone);
    }

    private static ZoneId zone(String id) {
        try { return ZoneId.of(id == null ? "" : id.strip()); }
        catch (DateTimeException bad) { throw bad("timeZone must be an IANA zone such as Asia/Kolkata"); }
    }

    private Map<Integer, Optional<Sla>> overrides(long schoolId) {
        Map<Integer, Optional<Sla>> map = new HashMap<>();
        repo.schoolSla(schoolId).forEach(o -> map.put(o.stepOrder(), Optional.ofNullable(o.sla())));
        return map;
    }

    /** School override when present (even "none"), otherwise the definition default. */
    private Map<Integer, Optional<Sla>> effectiveSla(long schoolId) {
        Map<Integer, Optional<Sla>> overrides = overrides(schoolId);
        Map<Integer, Optional<Sla>> effective = new HashMap<>();
        for (DefinitionStep def : repo.definitionSteps()) {
            effective.put(def.stepOrder(), overrides.containsKey(def.stepOrder()) ? overrides.get(def.stepOrder()) : Optional.ofNullable(def.defaultSla()));
        }
        return effective;
    }

    private static TrackerView.Sla view(Sla sla, String source) { return new TrackerView.Sla(sla.value(), sla.unit().name(), source); }

    private TrackerView assemble(Actor actor, TrackerRow t) {
        List<StepRow> rows = repo.steps(t.instanceId());
        TrackerProgress progress = progress(t, rows);
        Map<Integer, Optional<Sla>> overrides = overrides(t.schoolId());
        Map<Integer, DefinitionStep> definitions = new HashMap<>();
        repo.definitionSteps().forEach(d -> definitions.put(d.stepOrder(), d));
        OffsetDateTime now = now();
        List<TrackerView.Step> steps = rows.stream().map(row -> {
            TrackerStep step = TrackerStep.ofNumber(row.stepOrder()).orElseThrow();
            DefinitionStep def = definitions.get(row.stepOrder());
            Optional<Sla> override = overrides.get(row.stepOrder());
            TrackerView.Sla sla = override != null ? override.map(s -> view(s, "SCHOOL")).orElse(null)
                    : def == null || def.defaultSla() == null ? null : view(def.defaultSla(), "DEFAULT");
            boolean active = "ACTIVE".equals(row.status());
            boolean worker = actor.role() == Role.SUPERADMIN || Objects.equals(actor.userId(), row.assigneeUserId());
            boolean canComplete = active && worker && (step != TrackerStep.HEAD_APPROVAL || actor.role() == Role.SUPERADMIN);
            TrackerView.Capabilities can = new TrackerView.Capabilities(
                    worker && progress.canEditDetails(actor.role(), step),
                    worker && progress.canEditStageNote(actor.role(), step), canComplete,
                    actor.role() == Role.SUPERADMIN && "COMPLETED".equals(row.status()));
            return new TrackerView.Step(step.number(), step.title(), step.customisedOnly(), row.status(), row.assigneeUserId(),
                    row.startedAt(), row.dueAt(), active && row.dueAt() != null && now.isAfter(row.dueAt()), sla,
                    row.completedAt(), row.completedBy(), row.data(), row.stageNote(), row.version(), can);
        }).toList();
        TrackerView.Contact vendor = t.vendorName() == null ? null : new TrackerView.Contact(t.vendorName(), t.vendorPhone());
        TrackerView.Designer designer = t.designerId() == null ? null
                : repo.designer(t.designerId()).map(d -> new TrackerView.Designer(d.id(), d.name(), d.phone())).orElse(null);
        TrackerView.Design design = t.customised() ? new TrackerView.Design(t.designState(), t.designStateNote(), t.designStateAt(),
                t.designStateBy(), t.approvedDraftRef(), progress.canManageDraftImages(actor.role(), "CHANGES_REQUESTED".equals(t.designState()))) : null;
        Optional<TrackerStep> current = progress.current();
        return new TrackerView(t.orderId(), t.schoolId(), t.customised(), t.orderedCount(), t.placedAt(), t.timeZone(), t.schoolName(), t.instanceStatus(),
                new TrackerView.Progress(progress.completedCount(), progress.total(), current.map(TrackerStep::number).orElse(null),
                        current.map(TrackerStep::title).orElse(null), progress.finished()),
                vendor, designer, design, steps,
                repo.internalNotes(t.instanceId()).stream().map(n -> new TrackerView.Note(n.id(), n.authorUserId(), n.body(), n.createdAt())).toList(),
                repo.followUps(t.instanceId()).stream().map(f -> new TrackerView.FollowUp(f.id(), f.percentComplete(), f.nextFollowUpOn(),
                        f.contactPhone(), f.createdBy(), f.createdAt())).toList(),
                t.version());
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
}
