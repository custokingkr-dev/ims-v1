"""The order statuses school-core accepts from the order workflow engine match operations' workflow templates.

School-core shows a tracker-managed order's current step code as its status (item order workflows design D5). It accepts
any code matching CatalogOrderFormService.WORKFLOW_STATUS except the order flow's own statuses (ORDER_FLOW_STATUSES);
operations sends the code of the order's current step from its template JSON, or COMPLETED once every step is done. The
two sides live in different services, so this reads both and fails when a template code would be refused or would be
mistaken for an order-flow status. It also pins the seams that cross a deploy:

- the notebook template keeps the twelve codes the notebook tracker always sent, so existing orders' statuses and a
  school-core deployed before the item order workflows keep working;
- the retired product workflow's (catalog V36) stage codes, mapped by NotebookTrackerCreationQueue.importedStepCode, land
  on a step of every nine-step template, so imported progress is never dropped.

Standard library only.
"""
import json
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHOOL_CORE = ROOT / "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogOrderFormService.java"
CREATION_QUEUE = ROOT / "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/NotebookTrackerCreationQueue.java"
TEMPLATES = ROOT / "services/operations-service/src/main/resources/workflow-templates"

COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)

# The notebook tracker's step codes before the item order workflows (operations' retired TrackerStep enum).
LEGACY_NOTEBOOK_CODES = [
    "ORDER_DETAILS", "DESIGNING_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING", "BOARD_PROCUREMENT",
    "PRE_PRINT_QUALITY_GATE", "PRINTING_PRESS", "FACTORY_HANDOVER", "PRODUCTION_FOLLOW_UP", "VALIDATION_COUNT",
    "DELIVERY_INVOICING",
]
# The retired PR #380 product workflow's stage codes (catalog V36; read-only since V38).
V36_STAGE_CODES = [
    "ORDER_DETAILS", "DESIGN_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING", "PRE_PRINT_QUALITY_CHECK",
    "PRODUCTION_FOLLOW_UP", "FINAL_VERIFICATION", "DELIVERY_INVOICING",
]


def accepted_pattern(java_source):
    """The regex of `WORKFLOW_STATUS = ...Pattern.compile("...")`, with Java string escapes undone."""
    source = COMMENTS.sub("", java_source)
    match = re.search(r"\bWORKFLOW_STATUS\s*=\s*[\w.]*Pattern\.compile\(\"((?:\\.|[^\"\\])*)\"\)", source)
    if not match:
        raise AssertionError("WORKFLOW_STATUS = Pattern.compile(\"...\") not found")
    return re.compile(match.group(1).replace("\\\\", "\\"))


def refused_statuses(java_source):
    """The literals of `ORDER_FLOW_STATUSES = Set.of(...)`: the order flow's statuses, which a push may never set."""
    source = COMMENTS.sub("", java_source)
    match = re.search(r"\bORDER_FLOW_STATUSES\s*=\s*Set\.of\((.*?)\)\s*;", source, re.S)
    if not match:
        raise AssertionError("ORDER_FLOW_STATUSES = Set.of(...) not found")
    tokens = [part.strip() for part in match.group(1).split(",")]
    codes = set()
    for token in tokens:
        literal = re.fullmatch(r"\"([^\"]*)\"", token)
        if not literal:
            raise AssertionError("ORDER_FLOW_STATUSES names something this test cannot resolve: " + token)
        codes.add(literal.group(1))
    return codes


def imported_step_mapping(java_source):
    """`importedStepCode`'s switch: V36 stage code to engine step code; any other code maps to itself."""
    source = COMMENTS.sub("", java_source)
    match = re.search(r"\bstatic\s+String\s+importedStepCode\s*\(\s*String\s+(\w+)\s*\)\s*\{(.*?)\n\s*\}", source, re.S)
    if not match:
        raise AssertionError("static String importedStepCode(String ...) not found")
    parameter, body = match.group(1), match.group(2)
    if not re.search(r"default\s*->\s*" + re.escape(parameter) + r"\s*;", body):
        raise AssertionError("importedStepCode must map every other code to itself")
    return dict(re.findall(r"case\s+\"([A-Z0-9_]+)\"\s*->\s*\"([A-Z0-9_]+)\"\s*;", body))


def template_codes():
    """Each template's step codes in step order, by definition id."""
    templates = {}
    for path in sorted(TEMPLATES.glob("*.json")):
        template = json.loads(path.read_text(encoding="utf-8"))
        steps = sorted(template["steps"], key=lambda step: step["order"])
        templates[template["definitionId"]] = [step["code"] for step in steps]
    return templates


class TrackerStatusCodesTest(unittest.TestCase):
    def setUp(self):
        school_core = SCHOOL_CORE.read_text(encoding="utf-8")
        self.pattern = accepted_pattern(school_core)
        self.refused = refused_statuses(school_core)
        self.templates = template_codes()

    def test_school_core_accepts_every_template_step_and_completed(self):
        self.assertEqual({"NOTEBOOK_ORDER_TRACKER", "FLIER_ORDER_WORKFLOW", "FLEX_ORDER_WORKFLOW", "BILL_BOOK_ORDER_WORKFLOW",
                          "GENERAL_ITEM_ORDER_WORKFLOW"}, set(self.templates))
        for definition, codes in self.templates.items():
            self.assertEqual(len(codes), len(set(codes)), definition)
            for code in codes + ["COMPLETED"]:
                with self.subTest(definition=definition, code=code):
                    self.assertTrue(self.pattern.fullmatch(code), code + " would be refused by school-core")
                    self.assertNotIn(code, self.refused, code + " is an order-flow status, so school-core refuses it")
        self.assertNotIn("COMPLETED", self.refused)

    def test_the_notebook_template_keeps_the_trackers_step_codes(self):
        self.assertEqual(LEGACY_NOTEBOOK_CODES, self.templates["NOTEBOOK_ORDER_TRACKER"])

    def test_imported_v36_stages_land_on_every_nine_step_template(self):
        mapping = imported_step_mapping(CREATION_QUEUE.read_text(encoding="utf-8"))
        self.assertTrue(mapping, "importedStepCode maps no code")
        for stage in mapping:
            self.assertIn(stage, V36_STAGE_CODES, stage + " is not a V36 stage code")
        imported = [mapping.get(code, code) for code in V36_STAGE_CODES]
        for definition, codes in self.templates.items():
            if definition == "NOTEBOOK_ORDER_TRACKER":
                continue
            with self.subTest(definition=definition):
                self.assertEqual(imported, codes, "every V36 stage maps, in order, onto a step of " + definition)

    def test_the_parsers_see_a_drifted_copy(self):
        school_core = r'''
            static final java.util.regex.Pattern WORKFLOW_STATUS = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
            // static final java.util.regex.Pattern WORKFLOW_STATUS = Pattern.compile("^NOPE$");
            static final Set<String> ORDER_FLOW_STATUSES = Set.of("DRAFT", "CANCELLED",
                    "DELIVERED");
        '''
        pattern = accepted_pattern(school_core)
        self.assertTrue(pattern.fullmatch("PRINTING_PRESS"))
        self.assertFalse(pattern.fullmatch("printing_press"))
        self.assertFalse(pattern.fullmatch("A"))
        self.assertEqual({"DRAFT", "CANCELLED", "DELIVERED"}, refused_statuses(school_core))
        with self.assertRaises(AssertionError):
            refused_statuses('static final Set<String> ORDER_FLOW_STATUSES = Set.of(SOME_CONSTANT);')
        queue = '''
            static String importedStepCode(String v36StageCode) {
                return switch (v36StageCode) {
                    case "DESIGN_ACTIVITY" -> "DESIGNING_ACTIVITY";
                    // case "OLD" -> "GONE";
                    default -> v36StageCode;
                };
            }
        '''
        self.assertEqual({"DESIGN_ACTIVITY": "DESIGNING_ACTIVITY"}, imported_step_mapping(queue))
        with self.assertRaises(AssertionError):
            imported_step_mapping(queue.replace("default -> v36StageCode;", "default -> null;"))


if __name__ == "__main__":
    unittest.main()
