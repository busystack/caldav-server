import importlib.util
from pathlib import Path
import unittest
import os
from unittest.mock import patch
from datetime import datetime, timedelta, timezone

spec=importlib.util.spec_from_file_location('container_gate',Path(__file__).resolve().parents[2]/'scripts/check-container-advisories.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class ContainerGateTest(unittest.TestCase):
    def setUp(self):
        self.now=datetime.now(timezone.utc)
        self.feed={'UpdatedAt':self.now.isoformat()}
        self.doc={'Metadata':{'OS':{'Family':'ubuntu','Name':'26.04'}},'Results':[{'Class':'os-pkgs','Packages':[{'Name':'libc6'}]}]}
    def test_valid_inventory_is_not_empty_clean_placeholder(self):
        self.assertEqual(gate.analyze(self.doc,self.feed,self.now)['status'],'passed')
    def test_missing_inventory_fails_closed(self):
        self.doc['Results'][0]['Packages']=[]
        with self.assertRaises(ValueError): gate.analyze(self.doc,self.feed,self.now)
    def test_stale_feed_is_not_passed(self):
        self.feed['UpdatedAt']=(self.now-timedelta(days=3)).isoformat()
        with self.assertRaises(ValueError): gate.analyze(self.doc,self.feed,self.now)
    def test_high_unfixed_advisory_blocks_without_suppression(self):
        self.doc['Results'][0]['Vulnerabilities']=[{'VulnerabilityID':'test','Severity':'HIGH','Status':'affected'}]
        result=gate.analyze(self.doc,self.feed,self.now)
        self.assertEqual(result['status'],'advisories-returned');self.assertEqual(result['blockingFindings'],1)
    def test_lower_findings_remain_visible(self):
        self.doc['Results'][0]['Vulnerabilities']=[{'VulnerabilityID':'test','Severity':'MEDIUM'}]
        result=gate.analyze(self.doc,self.feed,self.now)
        self.assertEqual(result['status'],'passed');self.assertEqual(len(result['findings']),1)

    def test_missing_or_unrecognized_severity_fails_closed(self):
        for severity in (None, '', 'UNREVIEWED', 'high', 17):
            with self.subTest(severity=severity):
                item={'VulnerabilityID':'test'}
                if severity is not None: item['Severity']=severity
                self.doc['Results'][0]['Vulnerabilities']=[item]
                with self.assertRaisesRegex(ValueError,'severity'):
                    gate.analyze(self.doc,self.feed,self.now)

    def test_shell_overrides_cannot_silently_suppress_or_weaken_the_scan(self):
        with patch.dict(os.environ,{'TRIVY_IGNORE_UNFIXED':'true','TRIVY_SEVERITY':'CRITICAL','TRIVY_INSECURE':'true','DOCKER_HOST':'unix:///fixture/docker.sock'},clear=True):
            self.assertEqual(gate.scanner_environment(),{'DOCKER_HOST':'unix:///fixture/docker.sock'})
