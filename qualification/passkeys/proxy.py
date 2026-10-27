"""Optional isolated HTTPS reverse proxy for the same real-browser qualification."""

import http.client
import json
from pathlib import Path
import ssl
import urllib.error
import urllib.parse
import urllib.request
import tls


IMAGE = "nginx:1.30.6-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951"


def override(root, directory, application_image, base, port):
    public = urllib.parse.urlsplit(base)
    assert public.path == "/team"
    # A host-private directory protects the bind-mounted fixture key from other host users.
    (directory / "server.key").chmod(0o444)
    template = (root / "qualification/proxy/nginx.conf.template").read_text()
    config = template.replace("@@PREFIX@@", public.path).replace("@@ORIGIN@@", f"{public.scheme}://{public.netloc}").replace("@@AUTHORITY@@", public.netloc).replace("@@HOSTNAME@@", public.hostname)
    (directory / "nginx.conf").write_text(config)
    return (
        "services:\n  application:\n"
        f"    image: {json.dumps(application_image)}\n"
        "    ports: !override []\n"
        "    environment:\n      SERVER_PORT: '8080'\n      SERVER_SSL_ENABLED: 'false'\n"
        "  reverse-proxy:\n"
        f"    image: {IMAGE}\n"
        "    user: '101:101'\n"
        "    entrypoint: ['nginx', '-g', 'daemon off;']\n"
        "    depends_on:\n      application:\n        condition: service_healthy\n"
        f"    ports:\n      - '127.0.0.1:{port}:8443'\n"
        "    volumes:\n"
        f"      - {json.dumps(str(directory / 'nginx.conf') + ':/etc/nginx/nginx.conf:ro')}\n"
        f"      - {json.dumps(str(directory / 'server.pem') + ':/tls/server.pem:ro')}\n"
        f"      - {json.dumps(str(directory / 'server.key') + ':/tls/server.key:ro')}\n"
        "    read_only: true\n    tmpfs:\n      - /tmp:rw,nosuid,nodev,noexec,size=32m\n"
        "    mem_limit: 128m\n    cap_drop: [ALL]\n    security_opt: ['no-new-privileges:true']\n"
    )


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify(base, ca):
    public = urllib.parse.urlsplit(base)
    origin = f"{public.scheme}://{public.netloc}"
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.load_verify_locations(cafile=ca)
    opener = tls.opener(ca, NoRedirect)

    def call(url, method="GET", headers=None, data=None):
        try:
            response = opener.open(urllib.request.Request(url, method=method, headers=headers or {}, data=data), timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            return response.status, response.headers, response.read(65536)

    status, headers, _ = call(origin + "/.well-known/caldav")
    assert status == 308 and headers["Location"] == base + "/dav/"
    forged = {"Host": "attacker.invalid", "Forwarded": "host=attacker.invalid;proto=http", "X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "http"}
    status, _, body = call(origin + "/.well-known/oauth-authorization-server" + public.path, headers=forged)
    assert status == 200 and json.loads(body)["issuer"] == base
    status, headers, _ = call(base, headers=forged)
    assert status == 308 and headers["Location"] == base + "/"
    status, headers, _ = call(base + "/security/passkeys", headers={**forged, "Accept": "text/html"})
    target = urllib.parse.urlsplit(headers.get("Location", ""))
    assert status == 302 and (target.scheme, target.netloc, target.path) == (public.scheme, public.netloc, public.path + "/login"), f"Expected configured browser login redirect; status={status}, location={headers.get('Location')}"
    assert urllib.parse.parse_qs(target.query) in ({}, {"factor": ["required"]}) and not target.fragment
    for path in ("/actuator", "/actuator/health", public.path + "/actuator", public.path + "/actuator/health/readiness"):
        assert call(origin + path)[0] == 404
    for method in ("OPTIONS", "PROPFIND", "PROPPATCH", "REPORT", "MKCALENDAR", "MKCOL", "GET", "HEAD", "PUT", "DELETE", "COPY", "MOVE", "ACL"):
        status, headers, _ = call(base + "/dav/", method=method)
        assert status == 401 and "Basic" in headers.get("WWW-Authenticate", ""), method
    assert call(base + "/dav/", method="LOCK")[0] in (400, 405, 501)
    assert call(base + "/dav/", method="PUT", data=b"x" * (1048576 + 1))[0] == 413
    connection = tls.Connection(public.hostname, public.port, context=context, timeout=15)
    try:
        try:
            connection.request("PUT", public.path + "/dav/", body=(b"x" * 65536 for _ in range(17)), headers={"Content-Type": "application/xml"}, encode_chunked=True)
        except BrokenPipeError:
            pass  # The server may reject an oversized stream before the client finishes writing it.
        response = connection.getresponse()
        assert response.status == 413
        response.read()
    finally:
        connection.close()
    print("PROXY_TEAM_DISCOVERY_METADATA_PRIVACY_METHODS_AND_BODY_LIMITS_PASSED", flush=True)
