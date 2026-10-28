"""Run the existing small correctness profiles against the browser-issued packaged credential."""

import json
import os
from pathlib import Path
import subprocess


def verify(checkpoint, configuration):
    secret = checkpoint.directory / "packaged-client-secret"
    secret.write_text(checkpoint.saved["credential"])
    secret.chmod(0o600)
    environment = os.environ.copy()
    environment.update(
        BUSYSTACK_CLIENT_URL=checkpoint.base + "/dav/",
        BUSYSTACK_CLIENT_USER=checkpoint.saved["username"],
        BUSYSTACK_CLIENT_SECRET_FILE=str(secret),
        BUSYSTACK_CLIENT_CA=str(checkpoint.ca),
        BUSYSTACK_CLIENT_SYNC_DEPTH_INPUT="setting omitted; packaged application default",
        BUSYSTACK_THUNDERBIRD_BINARY=configuration["thunderbird"],
    )
    results = []
    try:
        for script, deadline in (("python_caldav.py", 180), ("thunderbird.py", 360)):
            result = subprocess.run(
                [configuration["python"], "qualification/clients/" + script],
                cwd=checkpoint.root, env=environment, capture_output=True, text=True, timeout=deadline,
            )
            if result.returncode:
                private = [checkpoint.saved["credential"], checkpoint.saved["encoded"],
                    checkpoint.saved["grant"]["accessToken"], checkpoint.saved["grant"]["refreshToken"]]
                message = checkpoint.operations.sanitized_diagnostic(result.stdout + result.stderr, private)
                raise RuntimeError("Packaged independent client " + script + " failed: " + message)
            value = json.loads(result.stdout.strip().splitlines()[-1])
            assert value["status"] == "passed", script
            results.append(value)
            print("PACKAGED_INDEPENDENT_CLIENT=" + json.dumps(value, sort_keys=True), flush=True)
    finally:
        secret.unlink(missing_ok=True)
    output = checkpoint.root / "target/qualification/standards-corrections-packaged-clients.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"applicationImage": configuration["image"],
        "sourceRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkpoint.root, text=True).strip(),
        "profile": "actual packaged HTTPS server, default sync compatibility; small correctness profiles without performance workload",
        "clients": results}, indent=2) + "\n")
