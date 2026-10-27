"""Adversarial feed cases for a CI gate that must never report partial data as success."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("advisories", ROOT / "scripts/check-advisories.py")
ADVISORIES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ADVISORIES)


class AdvisoryGateTest(unittest.TestCase):
    def test_only_pending_query_is_paginated_and_advisories_are_retained(self):
        packages, _ = ADVISORIES.coordinates({"bomFormat": "CycloneDX", "components": [
            {"purl": "pkg:maven/test/a@1", "group": "test", "name": "a", "version": "1"},
            {"purl": "pkg:maven/test/b@1", "group": "test", "name": "b", "version": "1"},
        ]})
        requests = []

        def feed(payload):
            requests.append(payload)
            if len(requests) == 1:
                return {"results": [{"vulns": [{"id": "ADVISORY-1"}], "next_page_token": "next"}, {}]}
            self.assertEqual(payload["queries"], [{"package": {"ecosystem": "Maven", "name": "test:a"}, "version": "1", "page_token": "next"}])
            return {"results": [{"vulns": [{"id": "ADVISORY-2"}]}]}

        ADVISORIES.query(packages, feed)
        self.assertTrue(all(package["complete"] for package in packages))
        self.assertEqual([value["id"] for value in packages[0]["advisories"]], ["ADVISORY-1", "ADVISORY-2"])

    def test_missing_results_and_repeated_pagination_tokens_fail(self):
        for feed in (
            lambda _: {"results": []},
            lambda _: {"results": [{"next_page_token": "unchanged"}]},
        ):
            with self.subTest(feed=feed):
                packages = [{"name": "test:a", "version": "1", "advisories": [], "pages": 0, "complete": False}]
                with self.assertRaises(ValueError):
                    ADVISORIES.query(packages, feed)

    def test_feed_failure_and_returned_advisory_have_distinct_nonzero_outcomes(self):
        with tempfile.TemporaryDirectory() as directory:
            sbom, output = Path(directory) / "input.json", Path(directory) / "result.json"
            sbom.write_text(json.dumps({"bomFormat": "CycloneDX", "components": [
                {"purl": "pkg:maven/test/a@1", "group": "test", "name": "a", "version": "1"},
            ]}))

            def unavailable(_):
                raise urllib.error.URLError("feed unavailable")

            self.assertEqual(ADVISORIES.run(sbom, output, unavailable), 2)
            self.assertEqual(json.loads(output.read_text())["status"], "error")
            self.assertEqual(ADVISORIES.run(sbom, output, lambda _: {"results": [{"vulns": [{"id": "ADVISORY-1"}]}]}), 1)
            self.assertEqual(json.loads(output.read_text())["status"], "advisories-returned")

    def test_empty_inventory_cannot_pass(self):
        with self.assertRaises(ValueError):
            ADVISORIES.coordinates({"bomFormat": "CycloneDX", "components": []})


if __name__ == "__main__":
    unittest.main()
