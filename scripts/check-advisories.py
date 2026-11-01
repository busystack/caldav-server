#!/usr/bin/env python3
"""Check runtime Maven coordinates against the official OSV API; fail closed on incomplete data."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import urllib.error
import urllib.request


API = "https://api.osv.dev/v1/querybatch"
MAX_RESPONSE = 32 * 1024 * 1024


def coordinates(document):
    if not isinstance(document, dict) or document.get("bomFormat") != "CycloneDX":
        raise ValueError("Input must be a generated CycloneDX JSON inventory")
    found = set()
    ignored = 0
    for component in document.get("components", []):
        if not isinstance(component, dict) or not isinstance(component.get("purl"), str):
            raise ValueError("Inventory contains an invalid component")
        if not component["purl"].startswith("pkg:maven/"):
            ignored += 1
            continue
        values = tuple(component.get(key) for key in ("group", "name", "version"))
        if any(not isinstance(value, str) or not value or "${" in value for value in values):
            raise ValueError("Maven component has missing or unresolved coordinates")
        if values[2].endswith("-SNAPSHOT"):
            raise ValueError("Runtime dependency inventory contains a snapshot")
        found.add((values[0] + ":" + values[1], values[2]))
    if not found:
        raise ValueError("Inventory contains no resolved Maven runtime dependencies")
    return [
        {"ecosystem": "Maven", "name": name, "version": version, "advisories": [], "pages": 0, "complete": False}
        for name, version in sorted(found)
    ], ignored


def request(payload):
    query = urllib.request.Request(
        API,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "BusyStack-runtime-advisory-check/1"},
        method="POST",
    )
    with urllib.request.urlopen(query, timeout=45) as response:
        if response.status != 200:
            raise ValueError("OSV returned a non-success status")
        data = response.read(MAX_RESPONSE + 1)
        if len(data) > MAX_RESPONSE:
            raise ValueError("OSV response exceeded the supported size")
        return json.loads(data)


def query(packages, post=request):
    pending = [
        (index, {"package": {"ecosystem": "Maven", "name": package["name"]}, "version": package["version"]})
        for index, package in enumerate(packages)
    ]
    tokens = [set() for _ in packages]
    requests = 0
    while pending:
        following = []
        for start in range(0, len(pending), 100):
            batch = pending[start:start + 100]
            response = post({"queries": [item for _, item in batch]})
            requests += 1
            if not isinstance(response, dict):
                raise ValueError("OSV returned an invalid response envelope")
            results = response.get("results")
            if not isinstance(results, list) or len(results) != len(batch):
                raise ValueError("OSV response omitted query results")
            for (index, sent), result in zip(batch, results):
                if not isinstance(result, dict) or "error" in result:
                    raise ValueError("OSV returned an invalid or failed query result")
                vulnerabilities = result.get("vulns", [])
                if not isinstance(vulnerabilities, list):
                    raise ValueError("OSV returned invalid advisory data")
                existing = {value["id"]: value for value in packages[index]["advisories"]}
                for value in vulnerabilities:
                    if not isinstance(value, dict) or not isinstance(value.get("id"), str) or not value["id"]:
                        raise ValueError("OSV returned an advisory without an identifier")
                    existing[value["id"]] = value
                packages[index]["advisories"] = sorted(existing.values(), key=lambda value: value["id"])
                packages[index]["pages"] += 1
                token = result.get("next_page_token")
                if token is not None and not isinstance(token, str):
                    raise ValueError("OSV returned an invalid pagination token")
                if token:
                    if token in tokens[index] or packages[index]["pages"] >= 100:
                        raise ValueError("OSV pagination did not complete within supported bounds")
                    tokens[index].add(token)
                    following.append((index, dict(sent, page_token=token)))
                else:
                    packages[index]["complete"] = True
        pending = following
    return requests


def write(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".osv-report-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(report, output, indent=2)
            output.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run(sbom, output, post=request):
    report = {"api": API, "checkedAt": datetime.now(timezone.utc).isoformat(), "status": "error"}
    try:
        data = sbom.read_bytes()
        packages, ignored = coordinates(json.loads(data))
        report.update(sbomSha256=hashlib.sha256(data).hexdigest(), packages=packages, ignoredNonMavenComponents=ignored)
        report["requestCount"] = query(packages, post)
        if not all(package["complete"] for package in packages):
            raise ValueError("Advisory response was incomplete")
        affected = sum(bool(package["advisories"]) for package in packages)
        report.update(status="advisories-returned" if affected else "no-advisories-returned", packageCount=len(packages), affectedPackageCount=affected)
        write(output, report)
        print(f"OSV: {len(packages)} Maven package versions checked; {affected} with returned advisories; report={output}")
        return 1 if affected else 0
    except (OSError, ValueError, TypeError, urllib.error.URLError) as error:
        report["error"] = str(error)
        write(output, report)
        print("Advisory check could not complete: " + str(error), file=sys.stderr)
        return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, default=Path("target/classes/META-INF/sbom/application.cdx.json"))
    parser.add_argument("--output", type=Path, default=Path("target/osv-runtime-advisories.json"))
    args = parser.parse_args()
    return run(args.sbom, args.output)


if __name__ == "__main__":
    sys.exit(main())
