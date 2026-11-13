package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The template loader (design D2, §3.3): the format, the defaults it fills in and the consistency checks it enforces. */
class TemplateLoaderTest {
    private static final String MINIMAL = TemplateFixtures.text("minimal/MINIMAL_WORKFLOW.json");
    private static final String SOURCE = "MINIMAL_WORKFLOW.json";

    /** The minimal fixture with one piece of text replaced; fails the test if the text is not there. */
    private static String minimalWith(String target, String replacement) {
        assertTrue(MINIMAL.contains(target), "fixture text not found: " + target);
        return MINIMAL.replace(target, replacement);
    }

    /** The problems reported for a template, joined for matching. */
    private static String problems(String json) {
        return problems(json, SOURCE);
    }

    private static String problems(String json, String source) {
        InvalidTemplateException invalid = assertThrows(InvalidTemplateException.class, () -> WorkflowTemplateLoader.parse(json, source));
        return String.join("\n", invalid.problems());
    }

    private static void assertProblem(String expected, String json) {
        String reported = problems(json);
        assertTrue(reported.contains(expected), "expected a problem containing [" + expected + "] but got:\n" + reported);
    }

    @Test
    void aValidTemplateLoadsWithEachKindsLimitsFilledIn() {
        WorkflowTemplate template = WorkflowTemplateLoader.parse(MINIMAL, SOURCE);
        assertEquals("MINIMAL_WORKFLOW", template.definitionId());
        assertEquals("My name is {Name}.", template.introMessage());
        StepSpec first = template.step(1).orElseThrow();
        assertEquals(Phase.INTAKE, first.phase());
        assertEquals(Sla.hours(2), first.defaultSla());
        assertEquals(List.of(Tab.WORK, Tab.ACTIVITY), first.tabs());
        assertEquals(List.of("link", "notes", "copies", "weight", "colour", "oldNote"), List.copyOf(first.fields().keySet()),
                "fields keep the file's order");
        assertEquals(new FieldSpec(FieldKind.URL, "Evidence link", null, null, 1000L, null, false), first.fields().get("link"));
        assertEquals(100L, first.fields().get("notes").max(), "WORDS default to 100 words");
        assertEquals(new FieldSpec(FieldKind.WHOLE, "Copies", null, 0L, 500L, null, false), first.fields().get("copies"));
        assertEquals(1L, first.fields().get("weight").min());
        assertEquals(List.of(List.of("RED", "Red"), List.of("BLUE", "Blue")), first.fields().get("colour").choices());
        assertEquals(Optional.of("Blue"), first.fields().get("colour").choiceLabel("BLUE"));
        assertEquals(new FieldSpec(FieldKind.TEXT, "Old note", null, null, 1000L, null, true), first.fields().get("oldNote"),
                "TEXT defaults to 1,000 characters");
        assertEquals(new ChecklistSpec(List.of("One", "Two"), null, "ours"), first.checklists().get("checks"));
        assertFalse(first.superadminCompletes());
        assertNull(first.signOff());

        StepSpec second = template.step(2).orElseThrow();
        assertNull(second.defaultSla(), "a stage without an sla has no timer");
        assertTrue(second.customisedOnly());
        assertEquals(List.of(), second.gates());
        assertEquals(List.of(), second.blocks());
        assertEquals(2, template.pathFor(true).size());
        assertEquals(1, template.pathFor(false).size());
        assertEquals("SECOND_STEP", template.stepByCode("SECOND_STEP").orElseThrow().code());
        assertTrue(template.step(3).isEmpty());
    }

    @Test
    void theNotebookFixtureLoadsWithItsSignOffsAndDerivedRules() {
        WorkflowTemplate nb = TemplateFixtures.NOTEBOOK;
        assertEquals(12, nb.steps().size());
        assertTrue(nb.step(4).orElseThrow().superadminCompletes(), "a HEAD_APPROVED stage is superadmin's to complete by default");
        assertFalse(nb.step(7).orElseThrow().superadminCompletes());
        assertEquals(new SignOffSpec("Record consignment sign-off", Role.SUPERADMIN, null), nb.step(12).orElseThrow().signOff(),
                "the role defaults to SUPERADMIN");
        assertEquals(7, nb.step(7).orElseThrow().checklists().get("checks").signOffItem());
        assertEquals(GateSpec.countsConsistent(List.of("producedCount", "acceptedCount", "dispatchCount"), "discrepancyNote"),
                nb.step(11).orElseThrow().gates().get(1), "the counts gate names its fields once loaded");
        assertEquals(List.of(List.of("NOT_INITIATED", "Not initiated"), List.of("INITIATED", "Initiated"), List.of("SENT", "Sent"),
                List.of("PAID", "Paid")), nb.step(12).orElseThrow().fields().get("invoiceStatus").choices(), "object-form choices load too");
        assertEquals(Optional.of("Mandatory pre-print quality gate"), nb.step(7).orElseThrow().checklistTitle("checks"));
        assertEquals(Map.of("FE", List.of("DIGITAL_PRINTERS", "OFFSET_PRINTERS", "PHOTO_PAPER_PRINTERS"), "FX", List.of("FLEX_PRINTERS"),
                "BLT", List.of()), TemplateFixtures.NINE_STEP.step(5).orElseThrow().blocks().get(3).suggestedByItem());
    }

    @Test
    void theLoaderRejectsAGateThatReferencesAnUndeclaredField() {
        assertProblem("MINIMAL_WORKFLOW.json: $.steps[0].gates[0].fields: names undeclared field missingLink",
                minimalWith("{\"type\": \"FIELDS_PRESENT\", \"fields\": [\"link\"]}", "{\"type\": \"FIELDS_PRESENT\", \"fields\": [\"missingLink\"]}"));
    }

    @Test
    void gateReferencesMustResolveToTheRightKindOfThing() {
        assertProblem("names undeclared checklist nope",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"ALL_CHECKED\", \"checklist\": \"nope\"}"));
        assertProblem("item 5 is not one of the checklist's items",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\", \"except\": [5]}"));
        assertProblem("leaves no item to tick",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\", \"except\": [0, 1]}"));
        assertProblem("GREEN is not a choice of colour",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"CHOICE_IN\", \"field\": \"colour\", \"values\": [\"GREEN\"]}"));
        assertProblem("link must be a CHOICE field",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"CHOICE_IN\", \"field\": \"link\", \"values\": [\"RED\"]}"));
        assertProblem("oldNote is a legacy field; it is read-only and cannot be required or edited",
                minimalWith("\"fields\": [\"link\"]}", "\"fields\": [\"oldNote\"]}"));
        assertProblem("producedCount",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"COUNTS_CONSISTENT\"}"));
        assertProblem("a SIGNED_OFF gate needs the stage to declare a signOff",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"SIGNED_OFF\"}"));
        assertProblem("$.steps[0].gates[1].type: must be one of",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"ALL_TICKED\", \"checklist\": \"checks\"}"));
        assertProblem("not_a_code is not a vendor category code",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"VENDORS_SELECTED\", \"categories\": [\"not_a_code\"]}"));
    }

    /** A misspelt key would silently weaken a gate, so keys that do not belong are refused; $ and _ keys are comments. */
    @Test
    void unknownKeysAreRefusedButCommentsAreIgnored() {
        assertProblem("$.steps[0].gates[1].exept: is not a known key here",
                minimalWith("{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\"}", "{\"type\": \"ALL_CHECKED\", \"checklist\": \"checks\", \"exept\": [1]}"));
        assertProblem("$.steps[0].gates[0].checklist: is not a known key here",
                minimalWith("{\"type\": \"FIELDS_PRESENT\", \"fields\": [\"link\"]}", "{\"type\": \"FIELDS_PRESENT\", \"fields\": [\"link\"], \"checklist\": \"checks\"}"));
        assertProblem("$.steps[0].blocks[1].save: is not a known key here",
                minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}", "{\"type\": \"CHECKLIST\", \"checklist\": \"checks\", \"save\": \"Save\"}"));
        assertProblem("$.steps[0].requiredFields: is not a known key here", minimalWith("\"order\": 1,", "\"order\": 1, \"requiredFields\": [],"));
        assertProblem("$.steps[0].fields.copies.maximum: is not a known key here", minimalWith("\"max\": 500", "\"maximum\": 500"));
        WorkflowTemplate commented = WorkflowTemplateLoader.parse(minimalWith("\"order\": 1,", "\"order\": 1, \"_note\": \"why\", \"$comment\": \"x\","), SOURCE);
        assertEquals(2, commented.steps().size());
    }

    @Test
    void stagesMustBeNumberedInOrderWithUniqueCodes() {
        assertProblem("$.steps[1].order: is 3; stages must be numbered 1 to n in file order, so this one is 2",
                minimalWith("\"order\": 2,", "\"order\": 3,"));
        assertProblem("$.steps[1].code: repeats stage code FIRST_STEP", minimalWith("\"SECOND_STEP\"", "\"FIRST_STEP\""));
        assertProblem("must not be COMPLETED", minimalWith("\"SECOND_STEP\"", "\"COMPLETED\""));
        assertProblem("$.steps[1].code: must match", minimalWith("\"SECOND_STEP\"", "\"second-step\""));
        assertProblem("$.steps[0].customisedOnly: the first stage must apply to every order",
                minimalWith("\"tabs\": [\"WORK\", \"ACTIVITY\"],", "\"tabs\": [\"WORK\", \"ACTIVITY\"], \"customisedOnly\": true,"));
    }

    @Test
    void theTemplateItselfMustBeComplete() {
        assertProblem("$.introMessage: must contain the {Name} placeholder", minimalWith("My name is {Name}.", "Hello."));
        assertTrue(problems(MINIMAL, "OTHER_WORKFLOW.json")
                .contains("OTHER_WORKFLOW.json: $.definitionId: is MINIMAL_WORKFLOW but the file must then be named MINIMAL_WORKFLOW.json"));
        assertProblem("$.steps[1].icon: is required", minimalWith("\"icon\": \"Truck\",", ""));
        assertProblem("$.steps[1].tabs: must list at least one tab", minimalWith("\"tabs\": [\"WORK\"]", "\"tabs\": []"));
        assertProblem("$.steps[1].tabs[1]: repeats WORK", minimalWith("\"tabs\": [\"WORK\"]", "\"tabs\": [\"WORK\", \"WORK\"]"));
        assertProblem("$.steps[0].sla.value: must be between 1 and 10000", minimalWith("{\"value\": 2, \"unit\": \"HOURS\"}", "{\"value\": 0, \"unit\": \"HOURS\"}"));
        assertProblem("$.steps[0].sla.unit: must be one of", minimalWith("{\"value\": 2, \"unit\": \"HOURS\"}", "{\"value\": 2, \"unit\": \"MINUTES\"}"));
        assertProblem("$.steps[0].phase: must be one of", minimalWith("\"phase\": \"INTAKE\"", "\"phase\": \"DESIGN\""));
    }

    @Test
    void fieldsAndChecklistsAreValidated() {
        assertProblem("$.steps[0].fields.colour.choices: a CHOICE field needs a non-empty list",
                minimalWith("\"choices\": [[\"RED\", \"Red\"], [\"BLUE\", \"Blue\"]]", "\"choices\": []"));
        assertProblem("repeats the value RED", minimalWith("[\"BLUE\", \"Blue\"]", "[\"RED\", \"Rouge\"]"));
        assertProblem("$.steps[0].fields.copies.max: must be a whole number", minimalWith("\"max\": 500", "\"max\": \"500\""));
        assertProblem("$.steps[0].fields.link.min: applies only to WHOLE and NUMBER fields",
                minimalWith("{\"kind\": \"URL\", \"label\": \"Evidence link\"}", "{\"kind\": \"URL\", \"label\": \"Evidence link\", \"min\": 1}"));
        assertProblem("$.steps[0].fields.link.max: must be between 20 and 1000",
                minimalWith("{\"kind\": \"URL\", \"label\": \"Evidence link\"}", "{\"kind\": \"URL\", \"label\": \"Evidence link\", \"max\": 5000}"));
        assertProblem("$.steps[0].fields.link.kind: must be one of",
                minimalWith("{\"kind\": \"URL\", \"label\": \"Evidence link\"}", "{\"kind\": \"LINK\", \"label\": \"Evidence link\"}"));
        assertProblem("$.steps[0].fields.link.label: is required",
                minimalWith("{\"kind\": \"URL\", \"label\": \"Evidence link\"}", "{\"kind\": \"URL\"}"));
        assertProblem("$.steps[0].fields.signOff: field keys must match", minimalWith("\"oldNote\":", "\"signOff\":"));
        assertProblem("$.steps[0].checklists.checks.source: must be one of",
                minimalWith("\"source\": \"ours\"", "\"source\": \"client\""));
        assertProblem("$.steps[0].checklists.checks.source: is required", minimalWith(", \"source\": \"ours\"", ""));
        assertProblem("$.steps[0].checklists.checks.signOffItem: needs the stage to declare a signOff",
                minimalWith("\"source\": \"ours\"", "\"source\": \"ours\", \"signOffItem\": 1"));
        assertProblem("$.steps[0].checklists.checks.items: must list between 1 and 64 items",
                minimalWith("\"items\": [\"One\", \"Two\"]", "\"items\": []"));
        assertProblem("$.steps[0].checklists.link: uses the same key as a field",
                minimalWith("\"checks\": {\"items\"", "\"link\": {\"items\": [\"A\"], \"source\": \"ours\"}, \"checks\": {\"items\""));
    }

    @Test
    void cardsAreValidated() {
        assertProblem("$.steps[0].blocks[0].fields: names undeclared field nothing",
                minimalWith("\"fields\": [\"link\", \"notes\", \"copies\", \"weight\"]", "\"fields\": [\"link\", \"nothing\"]"));
        assertProblem("oldNote is a legacy field", minimalWith("\"fields\": [\"link\", \"notes\", \"copies\", \"weight\"]", "\"fields\": [\"oldNote\"]"));
        assertProblem("$.steps[0].blocks[1].checklist: is required",
                minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}", "{\"type\": \"CHECKLIST\"}"));
        assertProblem("$.steps[0].blocks[1]: a SIGN_OFF card needs the stage to declare a signOff",
                minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}", "{\"type\": \"SIGN_OFF\"}"));
        assertProblem("either categories or suggestedByItem, not both",
                minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}",
                        "{\"type\": \"VENDOR_SELECTION\", \"categories\": [\"BINDERS\"], \"suggestedByItem\": {\"FE\": [\"BINDERS\"]}}"));
        assertProblem("item codes must match", minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}",
                "{\"type\": \"VENDOR_SELECTION\", \"suggestedByItem\": {\"flier\": [\"BINDERS\"]}}"));
        WorkflowTemplate legacyCard = WorkflowTemplateLoader.parse(minimalWith("{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}",
                "{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}, {\"type\": \"LEGACY_DETAILS\", \"fields\": [\"oldNote\"]}"), SOURCE);
        assertEquals(List.of("oldNote"), legacyCard.step(1).orElseThrow().blocks().get(2).fields(), "a legacy card shows legacy fields");
    }

    /** D28: the introduction card may keep the message as sent in one TEXT field of its stage, saved with the tick. */
    @Test
    void anIntroductionCardKeepsTheMessageAsSentInOneTextField() {
        String introCard = "{\"type\": \"CHECKLIST\", \"checklist\": \"checks\"}";
        String withText = minimalWith("\"oldNote\": {", "\"sentText\": {\"kind\": \"TEXT\", \"label\": \"Message as sent\", \"max\": 1000}, \"oldNote\": {");
        WorkflowTemplate intro = WorkflowTemplateLoader.parse(withText.replace(introCard,
                "{\"type\": \"INTRO_MESSAGE\", \"checklist\": \"checks\", \"fields\": [\"sentText\"]}"), SOURCE);
        assertEquals(List.of("sentText"), intro.step(1).orElseThrow().blocks().get(1).fields());

        String card = "{\"type\": \"INTRO_MESSAGE\", \"checklist\": \"checks\", \"fields\": %s}";
        assertProblem("$.steps[0].blocks[1].fields: notes must be a TEXT field",
                withText.replace(introCard, card.formatted("[\"notes\"]")));
        assertProblem("$.steps[0].blocks[1].fields: an introduction card keeps one field: the message as sent",
                withText.replace(introCard, card.formatted("[\"sentText\", \"oldNote\"]")));
        assertProblem("oldNote is a legacy field", withText.replace(introCard, card.formatted("[\"oldNote\"]")));
    }

    @Test
    void signOffsAreSuperadminsAndNeedALabel() {
        String signed = minimalWith("\"gates\": [{\"type\": \"FIELDS_PRESENT\"", "\"signOff\": {\"label\": \"Record approval\", \"role\": \"OPERATOR\"}, \"gates\": [{\"type\": \"FIELDS_PRESENT\"");
        assertProblem("$.steps[0].signOff.role: must be SUPERADMIN", signed);
        assertProblem("$.steps[0].signOff.label: is required",
                minimalWith("\"gates\": [{\"type\": \"FIELDS_PRESENT\"", "\"signOff\": {\"banner\": \"Blocked\"}, \"gates\": [{\"type\": \"FIELDS_PRESENT\""));
        String explicit = minimalWith("\"gates\": [{\"type\": \"FIELDS_PRESENT\"",
                "\"superadminCompletes\": true, \"signOff\": {\"label\": \"Record approval\"}, \"gates\": [{\"type\": \"SIGNED_OFF\"}, {\"type\": \"FIELDS_PRESENT\"");
        StepSpec step = WorkflowTemplateLoader.parse(explicit, SOURCE).step(1).orElseThrow();
        assertTrue(step.superadminCompletes());
        assertEquals("Record approval", step.signOff().label());
        assertTrue(step.hasGate(GateType.SIGNED_OFF));
    }

    @Test
    void malformedJsonAndDuplicateKeysAreRefused() {
        assertTrue(problems("{\"definitionId\": ").contains("is not valid JSON"));
        assertTrue(problems(minimalWith("\"title\": \"Minimal workflow\",", "\"title\": \"Minimal workflow\", \"title\": \"Again\","))
                .contains("is not valid JSON"), "a duplicate key would otherwise silently override the first");
        assertTrue(problems("[]").contains("$: must be an object"));
    }

    @Test
    void everyProblemInAFileIsReportedAtOnce() {
        String broken = minimalWith("\"SECOND_STEP\"", "\"FIRST_STEP\"").replace("\"fields\": [\"link\"]}", "\"fields\": [\"gone\"]}")
                .replace("My name is {Name}.", "Hi.");
        InvalidTemplateException invalid = assertThrows(InvalidTemplateException.class, () -> WorkflowTemplateLoader.parse(broken, SOURCE));
        assertEquals(3, invalid.problems().size(), String.join("\n", invalid.problems()));
        assertTrue(invalid.getMessage().startsWith("Invalid order workflow templates (3 problems):"));
    }

    @Test
    void loadAllFindsTheFilesAndRequiresEachDefinition() {
        List<WorkflowTemplate> loaded = WorkflowTemplateLoader.loadAll("classpath*:orderworkflow-fixtures/minimal/*.json", List.of("MINIMAL_WORKFLOW"));
        assertEquals(List.of("MINIMAL_WORKFLOW"), loaded.stream().map(WorkflowTemplate::definitionId).toList());
        InvalidTemplateException missing = assertThrows(InvalidTemplateException.class, () -> WorkflowTemplateLoader.loadAll(
                "classpath*:orderworkflow-fixtures/minimal/*.json", List.of("MINIMAL_WORKFLOW", "FLIER_ORDER_WORKFLOW")));
        assertEquals(List.of("classpath*:orderworkflow-fixtures/minimal/*.json: no template for required definition FLIER_ORDER_WORKFLOW"),
                missing.problems());
    }

    @Test
    void templatesAreLookedUpByDefinitionAndListedInTheDesignsOrder() {
        ClasspathWorkflowTemplates templates = new ClasspathWorkflowTemplates(List.of(TemplateFixtures.NINE_STEP, TemplateFixtures.NOTEBOOK,
                TemplateFixtures.load("minimal/MINIMAL_WORKFLOW.json")));
        assertEquals(List.of("NOTEBOOK_ORDER_TRACKER", "FLIER_ORDER_WORKFLOW", "MINIMAL_WORKFLOW"),
                templates.all().stream().map(WorkflowTemplate::definitionId).toList());
        assertSame(TemplateFixtures.NOTEBOOK, templates.get("NOTEBOOK_ORDER_TRACKER"));
        assertTrue(templates.find("FLEX_ORDER_WORKFLOW").isEmpty());
        WorkflowRuleViolation unknown = assertThrows(WorkflowRuleViolation.class, () -> templates.get("FLEX_ORDER_WORKFLOW"));
        assertEquals("UNKNOWN_WORKFLOW", unknown.code());
        assertThrows(IllegalArgumentException.class, () -> new ClasspathWorkflowTemplates(List.of(TemplateFixtures.NOTEBOOK, TemplateFixtures.NOTEBOOK)));
    }

    @Test
    void servedTemplatesCarryTheCurrentDefaultTimeSlots() {
        WorkflowTemplate edited = TemplateFixtures.NOTEBOOK.withDefaultSlas(Map.of(2, Optional.of(Sla.hours(48)), 6, Optional.empty()));
        assertEquals(Sla.hours(48), edited.step(2).orElseThrow().defaultSla());
        assertNull(edited.step(6).orElseThrow().defaultSla(), "an empty default means no timer");
        assertEquals(Sla.hours(2), edited.step(1).orElseThrow().defaultSla(), "stages left out keep the file's default");
        assertEquals(Sla.hours(72), TemplateFixtures.NOTEBOOK.step(2).orElseThrow().defaultSla(), "templates are immutable");
    }

    /** The served template has exactly the contract's properties (its schemas forbid additional ones). */
    @Test
    @SuppressWarnings("unchecked")
    void aServedTemplateSerialisesToExactlyTheContractsProperties() {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, Object> template = json.readValue(json.writeValueAsString(TemplateFixtures.NOTEBOOK), Map.class);
        assertEquals(Set.of("definitionId", "title", "introMessage", "steps"), template.keySet());
        Map<String, Object> step = ((List<Map<String, Object>>) template.get("steps")).get(6);
        assertEquals(Set.of("order", "code", "title", "description", "phase", "defaultSla", "customisedOnly", "icon", "tabs", "blocks",
                "fields", "checklists", "gates", "signOff", "superadminCompletes"), step.keySet());
        assertEquals(Map.of("value", 24, "unit", "HOURS"), step.get("defaultSla"));
        assertEquals(Set.of("kind", "label", "placeholder", "min", "max", "choices", "legacy"),
                ((Map<String, Map<String, Object>>) step.get("fields")).get("schoolVerification").keySet());
        assertEquals(Set.of("items", "signOffItem", "source"), ((Map<String, Map<String, Object>>) step.get("checklists")).get("checks").keySet());
        assertEquals(Set.of("type", "fields", "field", "values", "checklist", "except", "categories"),
                ((List<Map<String, Object>>) step.get("gates")).get(0).keySet());
        assertEquals(Set.of("type", "title", "fields", "checklist", "save", "categories", "suggestedByItem"),
                ((List<Map<String, Object>>) step.get("blocks")).get(0).keySet());
        assertEquals(Map.of("label", "Record reviewer sign-off", "role", "SUPERADMIN",
                "banner", "Bulk printing blocked until all checks and reviewer sign-off are complete."), step.get("signOff"));
        Map<String, Object> action = json.readValue(json.writeValueAsString(new UpcomingActions.UpcomingAction(UpcomingActions.Kind.CALLBACK,
                "Call back School", 2, null, UpcomingActions.DueState.NONE, false)), Map.class);
        assertEquals(Set.of("kind", "title", "stepOrder", "dueAt", "dueState", "done"), action.keySet());
    }

    /** The five shipped templates (written in WP1) must load, or the service would not start. */
    @Test
    void theShippedTemplatesLoad() {
        ClasspathWorkflowTemplates shipped = new ClasspathWorkflowTemplates();
        assertEquals(WorkflowTemplates.DEFINITION_IDS, shipped.all().stream().map(WorkflowTemplate::definitionId).toList());
        WorkflowTemplate notebook = shipped.get(WorkflowTemplates.NOTEBOOK);
        assertEquals(12, notebook.pathFor(true).size());
        assertEquals(9, notebook.pathFor(false).size());
        for (String nineStep : List.of(WorkflowTemplates.FLIER, WorkflowTemplates.FLEX, WorkflowTemplates.BILL_BOOK, WorkflowTemplates.GENERAL_ITEM)) {
            assertEquals(9, shipped.get(nineStep).pathFor(true).size(), nineStep);
            assertEquals(9, shipped.get(nineStep).pathFor(false).size(), nineStep);
        }
        assertTrue(notebook.stepByCode("HEAD_APPROVAL").orElseThrow().superadminCompletes());
    }
}
