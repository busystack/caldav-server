"""The large selective query has its measured allowance; other HTTP requests keep 60 seconds."""
import importlib.util
import io
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as XML

spec=importlib.util.spec_from_file_location('capacity_transport',Path(__file__).resolve().parents[1]/'clients/capacity.py')
capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)


class CapacityTransportTest(unittest.TestCase):
    def test_ordinary_request_keeps_sixty_second_transport_timeout(self):
        request=Request('https://fixture.invalid/calendar/')
        context=object();response=object()
        with patch.object(capacity,'urlopen',return_value=response) as open:
            self.assertIs(capacity.open_response(request,context),response)
        open.assert_called_once_with(request,context=context,timeout=60)

    def test_only_selective_query_supplies_its_measured_timeout(self):
        request=Mock(return_value=(207,{},b'<multistatus/>'))
        self.assertEqual(capacity.selective_query(request)[0],207)
        self.assertEqual(request.call_args.args,('REPORT',))
        self.assertEqual(request.call_args.kwargs['timeout'],150)
        body=XML.fromstring(request.call_args.kwargs['body'])
        self.assertEqual(body.tag,'{urn:ietf:params:xml:ns:caldav}calendar-query')
        self.assertEqual(body.find('.//{urn:ietf:params:xml:ns:caldav}time-range').attrib,{'start':'20261027T000000Z','end':'20261028T000000Z'})

    def test_http_limit_response_remains_a_response_not_a_timeout_retry(self):
        request=Request('https://fixture.invalid/calendar/')
        failure=HTTPError(request.full_url,507,'Limit',{},io.BytesIO(b'<error/>'))
        with patch.object(capacity,'urlopen',side_effect=failure) as open:
            self.assertIs(capacity.open_response(request,object(),timeout=150),failure)
        self.assertEqual(open.call_count,1)
