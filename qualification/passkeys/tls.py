"""Normal certificate validation with test-only routing for reserved .test domains."""

import http.client
import socket
import ssl
import urllib.request


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
    if ca is None:
        return ssl.create_default_context()
    value = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    value.load_verify_locations(cafile=ca)
    return value


def opener(ca=None, *handlers):
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), Handler(context=context(ca)), *handlers)
