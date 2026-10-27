"""Distribution regressions; actual archive and image checks run after packaging."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]

class DistributionMetadataTest(unittest.TestCase):
    def test_project_has_official_unmodified_apache_license(self):
        import hashlib
        self.assertEqual(hashlib.sha256((ROOT / 'LICENSE').read_bytes()).hexdigest(), 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30')

    def test_project_pom_declares_license_separately_from_dependencies(self):
        doc = ET.parse(ROOT / 'pom.xml')
        ns = {'m':'http://maven.apache.org/POM/4.0.0'}
        license = doc.find('m:licenses/m:license',ns)
        self.assertEqual(license.find('m:name',ns).text,'Apache License, Version 2.0')
        self.assertEqual(license.find('m:url',ns).text,'https://www.apache.org/licenses/LICENSE-2.0')

    def test_compose_and_executable_default_match_without_hidden_fixture_override(self):
        self.assertIn('${BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE:-true}',(ROOT/'compose.yaml').read_text())
        self.assertIn('allow-sync-depth-one: true',(ROOT/'src/main/resources/application.yaml').read_text())
        self.assertNotIn('allow-sync-depth-one=true',(ROOT/'src/test/java/org/busystack/caldav/protocol/IndependentClientIT.java').read_text())
        self.assertNotIn('"allowSyncDepthOne": True',(ROOT/'qualification/clients/python_caldav.py').read_text())

if __name__ == '__main__':
    unittest.main()
