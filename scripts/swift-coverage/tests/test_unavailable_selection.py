"""Test-selection metadata controls; these do not fabricate runtime evidence."""
import importlib.util
import re
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("ios_unavailable_selection", Path(__file__).resolve().parents[1] / "run_ios.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

NAME = "testContinuedProcessingOwnerMovesToRecreatedModuleWithoutRetainingOldOwner"
DRIVER = (runner.coverage.ROOT / "ios/tests/NativeCoverageTests.swift").read_text()
IOS18 = "com.apple.CoreSimulator.SimRuntime.iOS-18-6"


class UnavailableTestSelection(unittest.TestCase):
    def test_default_keeps_every_test_even_on_an_older_runtime(self):
        self.assertEqual([], runner.unavailable_test_arguments(IOS18, DRIVER, False))

    def test_explicit_older_runtime_selection_matches_the_original_suite(self):
        self.assertEqual(7, len(re.findall(r"@available\(iOS\s+26\.0\s*,\s*\*\)\s+func\s+test\w+\(", DRIVER)))
        self.assertEqual(["-skip-testing:TranslatePDFNativeCoverageTests/NativeCoverageTests/" + name
                          for name in (
                              NAME,
                              "testContinuedProcessingSubmitFailureDoesNotClaimBackgroundOwnership",
                              "testContinuedProcessingCallbackOnlyAttachesToAnAlreadyOwnedActiveRun",
                              "testContinuedProcessingAdmissionExpirationContractRejectsStaleGenerationAfterPauseResume",
                              "testQueuedPauseAndCancelAreAcknowledgedBeforeWorkerReturnsWithoutLeakingOwner",
                              "testFailedStopPersistenceRetriesThroughWorkerStopGateWithoutRestartingInference",
                              "testQueuedStopRetryCannotEnterSameJobsNewRunAfterItsTokenIsInvalidated",
                          )],
                         runner.unavailable_test_arguments(IOS18, DRIVER, True))

    def test_a_supported_or_unidentified_runtime_cannot_hide_the_test(self):
        for runtime in ["com.apple.CoreSimulator.SimRuntime.iOS-26-2", "com.apple.CoreSimulator.SimRuntime.iOS-27-0", "unknown", "iOS-18-6"]:
            with self.subTest(runtime=runtime), self.assertRaises(ValueError):
                runner.unavailable_test_arguments(runtime, DRIVER, True)

    def test_missing_or_changed_original_availability_is_rejected(self):
        for driver in ["func " + NAME + "() {}", DRIVER.replace("26.0", "18.0"), DRIVER.replace(NAME, "testSomethingElse")]:
            with self.subTest(driver=driver), self.assertRaises(ValueError):
                runner.unavailable_test_arguments(IOS18, driver, True)

    def test_stale_annotation_in_a_comment_cannot_hide_an_unannotated_method(self):
        stale = "@available(iOS 26.0,*)\n func " + NAME + "() {}"
        driver = "/* " + stale + " */\nfunc " + NAME + "() {}"
        with self.assertRaises(ValueError):
            runner.unavailable_test_arguments(IOS18, driver, True)

    def test_stale_annotation_in_a_multiline_string_cannot_hide_an_unannotated_method(self):
        stale = "@available(iOS 26.0,*)\n func " + NAME + "() {}"
        driver = 'let example = """\n' + stale + '\n"""\nfunc ' + NAME + '() {}'
        with self.assertRaises(ValueError):
            runner.unavailable_test_arguments(IOS18, driver, True)

    def test_any_unreviewed_source_change_rejects_the_finite_selection(self):
        with self.assertRaises(ValueError):
            runner.unavailable_test_arguments(IOS18, DRIVER + "\n// changed source\n", True)


if __name__ == "__main__":
    unittest.main()
