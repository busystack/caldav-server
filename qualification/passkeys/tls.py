"""Normal certificate validation with test-only routing for reserved .test domains."""

import http.client
import socket
import ssl
import urllib.request
import urllib.parse
import warnings
import json


DOMAINS = {"app.busystack.test", "idp.identity.test"}


class Connection(http.client.HTTPSConnection):
    def connect(self):
        if self.host in DOMAINS:
            # Route only these reserved fixture names; HTTPSConnection retains hostname/SNI checks.
            self._create_connection = lambda address, timeout, source_address=None: socket.create_connection(("127.0.0.1", address[1]), timeout, source_address)
        super().connect()


class Handler(urllib.request.HTTPSHandler):
    def https_open(self, request):
        return self.do_open(Connection, request, context=self._context)


def context(ca=None):
    return ssl.create_default_context(cafile=ca)


def opener(ca=None, *handlers):
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), Handler(context=context(ca)), *handlers)


def verify_profile(base, ca):
    """Negotiate the actual disposable listener, retaining CA, SNI and name checks."""
    public = urllib.parse.urlsplit(base)
    host = "127.0.0.1" if public.hostname in DOMAINS else public.hostname
    address = (host, public.port or 443)

    def handshake(value, name=None):
        with socket.create_connection(address, timeout=10) as raw:
            with value.wrap_socket(raw, server_hostname=name or public.hostname) as connection:
                return {"version": connection.version(), "cipher": connection.cipher()[0]}

    results = {}
    for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
        value = context(ca)
        value.minimum_version = value.maximum_version = version
        results[version.name] = handshake(value)
    assert handshake(context(ca))["version"] == "TLSv1.3"

    def rejected(value, name=None, certificate=False):
        try:
            handshake(value, name)
        except ssl.SSLCertVerificationError:
            assert certificate
        except ssl.SSLError as error:
            assert not certificate
            # A local no-ciphers failure would not establish rejection by the server.
            assert error.reason in ("TLSV1_ALERT_PROTOCOL_VERSION", "SSLV3_ALERT_HANDSHAKE_FAILURE")
        else:
            raise AssertionError("The deliberately unsupported TLS handshake succeeded")

    for version in (ssl.TLSVersion.TLSv1, ssl.TLSVersion.TLSv1_1):
        value = context(ca)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            value.minimum_version = value.maximum_version = version
        # Only this negative test client offers the obsolete version; server policy is unchanged.
        value.set_ciphers("ALL:@SECLEVEL=0")
        rejected(value)
        results[version.name] = "server rejected"
    for cipher in ("AES128-GCM-SHA256", "DHE-RSA-AES128-GCM-SHA256"):
        value = context(ca)
        value.minimum_version = value.maximum_version = ssl.TLSVersion.TLSv1_2
        value.set_ciphers(cipher)
        rejected(value)
        results[cipher] = "server rejected"
    rejected(context(), certificate=True)
    rejected(context(ca), name="wrong-host.invalid", certificate=True)
    results["identity"] = "trusted matching name succeeds; untrusted CA and wrong name rejected"
    print("HTTPS_TLS_PROFILE=" + json.dumps(results, sort_keys=True), flush=True)
