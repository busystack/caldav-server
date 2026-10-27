"""Inspect real ZIP structures; CycloneDX emits SPDX IDs without requiring redundant URLs."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('distribution_inspection',ROOT/'scripts/verify-distribution.py')
inspection=importlib.util.module_from_spec(spec);spec.loader.exec_module(inspection)


class JarLicenseContractTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='busystack-license-jar-test-')
        self.addCleanup(self.directory.cleanup)

    def jar(self, project_license, dependency_license='Broadcom Foundation License'):
        path=Path(self.directory.name)/'application.jar'
        session=[{'group':'org.springframework.session','name':module,'version':'4.1.1','licenses':[{'license':{'name':dependency_license}}]} for module in ('spring-session-core','spring-session-jdbc')]
        bom={'metadata':{'component':{'licenses':[{'license':project_license}]}},'components':session}
        with zipfile.ZipFile(path,'w') as jar:
            for name in inspection.DOCUMENTS:jar.writestr('META-INF/'+name,(ROOT/name).read_bytes())
            for name in ('ical4j-parser-BSD-3-Clause.txt','spring-derived-schemas.txt'):
                jar.writestr('META-INF/licenses/'+name,(ROOT/'src/main/resources/META-INF/licenses'/name).read_bytes())
            for name in ('asm-9.7.1-copyright-and-license.txt','logback-LICENSE.txt','EPL-2.0.txt','LGPL-2.1.txt'):
                jar.writestr('META-INF/licenses/'+name,(ROOT/'licenses'/name).read_bytes())
            jar.writestr('BOOT-INF/classes/application.yaml',(ROOT/'src/main/resources/application.yaml').read_bytes())
            jar.writestr('META-INF/sbom/application.cdx.json',json.dumps(bom))
            for component in session:
                nested=io.BytesIO()
                with zipfile.ZipFile(nested,'w') as dependency:dependency.writestr('META-INF/LICENSE.txt',(ROOT/'LICENSE').read_bytes())
                jar.writestr('BOOT-INF/lib/'+component['name']+'-4.1.1.jar',nested.getvalue())
        return path

    def test_canonical_spdx_only_project_license_is_accepted(self):
        result=inspection.inspect_jar(self.jar({'id':'Apache-2.0'}))
        self.assertEqual(result['runtimeComponents'],2)
        self.assertEqual(len(result['nestedArtifacts']),2)

    def test_official_optional_url_is_accepted(self):
        result=inspection.inspect_jar(self.jar({'id':'Apache-2.0','url':'https://www.apache.org/licenses/LICENSE-2.0'}))
        self.assertEqual(result['projectLicense'][0]['license']['id'],'Apache-2.0')

    def test_wrong_project_noassertion_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'Project SBOM license'):
            inspection.inspect_jar(self.jar({'name':'NOASSERTION'}))

    def test_optional_project_license_url_must_be_official(self):
        with self.assertRaisesRegex(AssertionError,'Project SBOM license'):
            inspection.inspect_jar(self.jar({'id':'Apache-2.0','url':'https://invalid.example/license'}))

    def test_original_dependency_metadata_cannot_be_overwritten_by_project_license(self):
        with self.assertRaisesRegex(AssertionError,'Original upstream POM'):
            inspection.inspect_jar(self.jar({'id':'Apache-2.0'},'Apache-2.0'))
