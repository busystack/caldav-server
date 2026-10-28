"""Operation failures retain bounded boot evidence while protecting fixture credentials."""
import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "verify_operations", Path(__file__).resolve().parents[2] / "scripts" / "verify-operations.py"
)
operations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operations)


class OperationDiagnosticsTest(unittest.TestCase):
    def test_health_attempts_are_bounded_sanitized_and_preserve_timing(self):
        attempts = [{"Start": str(index), "End": str(index + 1), "ExitCode": 1,
                     "Output": "\x1b[31mConnection refused fixture-secret password=short Bearer opaque\x1b[0m " + "bounded " * 1000}
                    for index in range(8)]
        state = {"State": {"Status": "running", "ExitCode": 0, "OOMKilled": False,
                           "StartedAt": "boot-time", "Health": {"Status": "unhealthy", "FailingStreak": 5, "Log": attempts}},
                 "RestartCount": 0, "Config": {"Healthcheck": {"StartPeriod": 60, "Interval": 15, "Timeout": 5, "Retries": 5,
                                                               "Test": ["credential-must-not-appear"]}}}
        result = operations.failure_state(state, ["fixture-secret"])
        self.assertEqual(result["startedAt"], "boot-time")
        self.assertEqual(result["healthcheck"], {"StartPeriod": 60, "Interval": 15, "Timeout": 5, "Retries": 5})
        self.assertEqual(len(result["healthLog"]), 5)
        self.assertEqual(result["healthLog"][0]["start"], "3")
        self.assertEqual(result["healthLog"][-1]["end"], "8")
        rendered = json.dumps(result)
        for secret in ("fixture-secret", "short", "opaque", "credential-must-not-appear", "\\u001b"):
            self.assertNotIn(secret, rendered)
        self.assertTrue(all(len(attempt["output"]) <= 1035 for attempt in result["healthLog"]))
        self.assertTrue(all(attempt["output"].endswith("[truncated]") for attempt in result["healthLog"]))
        self.assertIn("Connection refused", rendered)

    def test_startup_and_last_migrations_are_bounded_without_arbitrary_log_content(self):
        logs = "Starting BusyStackApplication\nPrivate calendar body must not be retained\n"
        logs += "\n".join("Migrating schema public to version " + str(index) for index in range(20))
        logs += "\nHikariPool-1 - Start completed\nTomcat initialized\n ERROR password=short fixture-secret"
        result = operations.startup_diagnostics(logs, ["fixture-secret"])
        self.assertEqual(len(result["lastMigrations"]), 8)
        self.assertTrue(result["lastMigrations"][0].endswith("12"))
        self.assertIn("Starting BusyStackApplication", result["startupMilestones"])
        rendered = json.dumps(result)
        self.assertNotIn("Private calendar body", rendered)
        self.assertNotIn("fixture-secret", rendered)
        self.assertNotIn("short", rendered)
        self.assertIn("Tomcat initialized", rendered)

    def test_short_authorization_values_are_redacted_before_named_header_handling(self):
        for value in ("Authorization: Basic dTpw", "Authorization=Bearer small-token"):
            rendered = operations.sanitized_diagnostic(value, [])
            self.assertNotIn("dTpw", rendered)
            self.assertNotIn("small-token", rendered)
