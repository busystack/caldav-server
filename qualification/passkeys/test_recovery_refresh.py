"""Retained grant bookkeeping must use actual public refresh, rotation and private storage."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs
import recovery


class RecoveryRefreshTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='busystack-recovery-refresh-test-')
        self.addCleanup(self.directory.cleanup)
        self.checkpoint=object.__new__(recovery.Checkpoint)
        self.checkpoint.exchange=Path(self.directory.name)/'private-exchange.json'
        self.checkpoint.saved={'grant':{'clientId':'public-native','accessToken':'old-access-'+'a'*50,'refreshToken':'old-refresh-'+'r'*50,'accessExpiresAt':0},'snapshotCookie':'private-session'}
        self.current={'access_token':'new-access-'+'a'*50,'refresh_token':'new-refresh-'+'r'*50,'expires_in':600,'token_type':'Bearer','scope':'dav'}
        self.sent=None
        def request(path,method,headers,body):
            self.sent=(path,method,headers,parse_qs(body.decode()))
            return 200,{},json.dumps(self.current).encode()
        self.checkpoint.request=request

    def test_actual_public_exchange_replaces_both_credentials_and_expiry_privately(self):
        with patch('recovery.time.time',return_value=1000): self.checkpoint.refresh_retained_grant()
        path,method,headers,form=self.sent
        self.assertEqual((path,method),('/oauth2/token','POST'))
        self.assertEqual(form,{'grant_type':['refresh_token'],'client_id':['public-native'],'refresh_token':['old-refresh-'+'r'*50]})
        stored=json.loads(self.checkpoint.exchange.read_text())
        self.assertEqual(stored['grant']['accessToken'],self.current['access_token'])
        self.assertEqual(stored['grant']['refreshToken'],self.current['refresh_token'])
        self.assertEqual(stored['grant']['accessExpiresAt'],1600000)
        self.assertEqual(self.checkpoint.exchange.stat().st_mode&0o777,0o600)
        self.assertEqual(stored['snapshotCookie'],'private-session')

    def test_missing_rotation_is_rejected_without_overwriting_exchange(self):
        self.current['refresh_token']=self.checkpoint.saved['grant']['refreshToken']
        with self.assertRaises(AssertionError): self.checkpoint.refresh_retained_grant()
        self.assertFalse(self.checkpoint.exchange.exists())

    def test_failed_endpoint_response_does_not_fabricate_fresh_expiry(self):
        self.checkpoint.request=lambda *args:(400,{},b'{"error":"invalid_grant"}')
        with self.assertRaises(AssertionError): self.checkpoint.refresh_retained_grant()
        self.assertEqual(self.checkpoint.saved['grant']['accessExpiresAt'],0)
        self.assertFalse(self.checkpoint.exchange.exists())

    def test_current_account_page_proves_live_snapshot_without_historical_heading(self):
        self.checkpoint.base='https://localhost:1234/team'
        self.checkpoint.saved.update(username='snapshot-user')
        body=b'<main><h1>Welcome</h1><span>snapshot-user</span></main><form method="post" action="/team/logout"><input name="_csrf" value="synthetic-csrf"><button>Sign out</button></form>'
        self.checkpoint.request=lambda *args,**kwargs:(200,{},body)
        self.checkpoint.verify_snapshot_session()

    def test_redirect_wrong_account_public_or_unprotected_page_cannot_prove_snapshot(self):
        self.checkpoint.base='https://localhost:1234/team'
        self.checkpoint.saved.update(username='snapshot-user')
        valid=b'<span>snapshot-user</span><form method="post" action="/team/logout"><input name="_csrf" value="synthetic-csrf"></form>'
        for status,body in ((302,valid),(200,valid.replace(b'snapshot-user',b'other-user')),
                (200,b'BusyStack account snapshot-user'),(200,valid.replace(b'value="synthetic-csrf"',b'value=""')),
                (200,valid.replace(b'/team/logout',b'/logout'))):
            with self.subTest(status=status,body=body):
                self.checkpoint.request=lambda *args,**kwargs:(status,{},body)
                with self.assertRaises(AssertionError):self.checkpoint.verify_snapshot_session()
