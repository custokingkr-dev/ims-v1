"""The order statuses school-core accepts from the notebook tracker match operations' tracker steps.

School-core shows a tracker-managed order's current step as its status and accepts only known codes
(CatalogOrderFormService.TRACKER_STATUSES); operations sends the names of its TrackerStep enum, or COMPLETED once every
step is done. The two copies live in different services, so this reads both sources and fails when they drift.
Standard library only.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHOOL_CORE = ROOT / "services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogOrderFormService.java"
TRACKER_STEP = ROOT / "services/operations-service/src/main/java/com/custoking/ims/operationsservice/application/tracker/TrackerStep.java"

COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)


def accepted_statuses(java_source):
    """The codes in `TRACKER_STATUSES = Set.of(...)`, resolving `static final String NAME = "..."` constants it names."""
    source = COMMENTS.sub("", java_source)
    match = re.search(r"\bTRACKER_STATUSES\s*=\s*Set\.of\((.*?)\)\s*;", source, re.S)
    if not match:
        raise AssertionError("TRACKER_STATUSES = Set.of(...) not found")
    constants = dict(re.findall(r"\bstatic\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*\"([^\"]*)\"\s*;", source))
    codes = set()
    for token in (part.strip() for part in match.group(1).split(",")):
        literal = re.fullmatch(r"\"([^\"]*)\"", token)
        if literal:
            codes.add(literal.group(1))
        elif token in constants:
            codes.add(constants[token])
        else:
            raise AssertionError("TRACKER_STATUSES names something this test cannot resolve: " + token)
    return codes


def step_names(java_source):
    """The constant names of `enum TrackerStep`, which operations sends as the order status."""
    source = COMMENTS.sub("", java_source)
    match = re.search(r"\benum\s+TrackerStep\s*\{(.*?);", source, re.S)
    if not match:
        raise AssertionError("enum TrackerStep { ...; not found")
    names = re.findall(r"(?:^|,)\s*([A-Z][A-Z0-9_]*)\s*\(", match.group(1))
    if not names:
        raise AssertionError("enum TrackerStep has no constants")
    return names


class TrackerStatusCodesTest(unittest.TestCase):
    def test_school_core_accepts_exactly_the_tracker_steps_and_completed(self):
        steps = step_names(TRACKER_STEP.read_text(encoding="utf-8"))
        self.assertEqual(12, len(steps), steps)
        self.assertEqual(len(steps), len(set(steps)))
        self.assertEqual(set(steps) | {"COMPLETED"}, accepted_statuses(SCHOOL_CORE.read_text(encoding="utf-8")))

    def test_the_parsers_see_a_drifted_copy(self):
        school_core = '''
            static final String FIRST = "ORDER_DETAILS"; // the first step
            /* static final String OLD = "GONE"; */
            static final Set<String> TRACKER_STATUSES = Set.of(FIRST, "PRINTING_PRESS",
                    "COMPLETED");
        '''
        self.assertEqual({"ORDER_DETAILS", "PRINTING_PRESS", "COMPLETED"}, accepted_statuses(school_core))
        enum = '''
            public enum TrackerStep {
                ORDER_DETAILS(1, "Order Details", false, Sla.hours(4)),
                // RETIRED(2, "Retired", false, null),
                PRINTING_PRESS(7, "Printing Press", false, Sla.hours(4)), NEW_STEP(13, "New", false, null);
                private final int number;
            }
        '''
        self.assertEqual(["ORDER_DETAILS", "PRINTING_PRESS", "NEW_STEP"], step_names(enum))
        self.assertNotEqual(set(step_names(enum)) | {"COMPLETED"}, accepted_statuses(school_core))
        with self.assertRaises(AssertionError):
            accepted_statuses('static final Set<String> TRACKER_STATUSES = Set.of(UNKNOWN_CONSTANT);')


if __name__ == "__main__":
    unittest.main()
