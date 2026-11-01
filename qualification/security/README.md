# Runtime dependency advisory checks

The later standards-correction artifact has [fresh advisory and licensing evidence](../evidence/standards-corrections/verification.json). Earlier retained records below qualify their identified historical revisions.

Generate the runtime CycloneDX inventory with `./mvnw package`, then run:

```sh
python3 -m unittest discover -s qualification/security -p 'test_*.py'
python3 scripts/check-advisories.py
```

The required CI gate reads resolved Maven group/artifact/version coordinates from the generated JSON and queries the official [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/). It sends public dependency coordinates only. It follows each query's pagination independently, verifies complete result cardinality, rejects nonadvancing pagination, and bounds response sizes and page counts. Feed/configuration errors return exit 2 and an error report; any returned advisory returns exit 1. Exit 0 means the API returned no advisories for those exact queried versions at the recorded time. It does not mean the code is certified safe or that the database knows every vulnerability.

`target/osv-runtime-advisories.json` records the inventory SHA-256, coordinates, completeness/page metadata, advisory identifiers and timestamp. Non-Maven components are counted separately; this check covers the Maven runtime graph, not container operating-system packages or application-code review. The job does not use an advisory-service account, secret, cached green result or a network-error bypass.

The positive control is inventory data for `org.apache.logging.log4j:log4j-core:2.14.1`. It does not download, execute or add that dependency to the application. It must return exit 1 and include the known Log4j advisory identifier `GHSA-jfh8-c2jp-5v3q`; an API error or an arbitrary nonzero exit is insufficient. Run it explicitly with:

```sh
python3 scripts/check-advisories.py \
  --sbom qualification/security/positive-control.cdx.json \
  --output target/osv-positive-control.json
```

The gate's unit tests cover partial pagination, missing results, repeated tokens, feed errors, detected advisories and empty inventories. CI runs the real positive control and the current application inventory, and retains the reports.

## Additional NVD audit

CI additionally invokes pinned OWASP Dependency-Check Maven 13.0.0 if an `NVD_API_KEY` secret is configured. This is additional coverage; OSV is the required advisory gate. A missing key is reported as an unexecuted NVD audit, not a passed NVD audit. When invoked, NVD errors or CVSS scores at least 7 fail its step. OSS Index is explicitly disabled because that source needs a separate credential.

An anonymous Dependency-Check 13.0.0 run was attempted on 2026-10-27 in an isolated Maven 3.9.12/Temurin 25 container, limited to 2 GiB and one CPU. The container received only a copy of the public dependency POM (SHA-256 `ef20da167a040dbc951b624d64696ee79614a92e311e7210652ffbfab9e0246f`) and a private report directory. It had no API-key environment variable or key-related command property. The equivalent scanner command was:

```sh
mvn --batch-mode --no-transfer-progress -f /scan/pom.xml \
  org.owasp:dependency-check-maven:13.0.0:check \
  -Dodc.outputDirectory=/reports -Dformats=HTML,JSON \
  -DfailBuildOnCVSS=7 -DfailOnError=true -DossIndexAnalyzerEnabled=false
```

It failed while updating NVD with `NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key`, followed by `NoDataException: No documents exist`. No NVD analysis completed and no passing NVD result is claimed. A working authenticated feed or separately verified fix/mirror is needed to qualify that additional source in this environment.

## Required final-container OS package scan

After building the final source image, run `python3 scripts/check-container-advisories.py --image busystack-caldav:ci`. The runner downloads **Trivy 0.76.1** from its official release, verifies archive SHA-256 `c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0`, then scans only the locally identified Docker image's OS packages. It records immutable image identity/labels, scanner version, complete package inventory, all reported findings, feed timestamps, database and raw-report hashes. The feed must be no more than 48 hours old and identify actual OS packages; update failures or missing output fail with exit 2. HIGH, CRITICAL and UNKNOWN findings fail with exit 1, including unfixed vulnerabilities. Missing or unrecognized severity fails closed; lower recognized severities remain visible for disposition. The scanner receives an empty private config/ignore file, all standard severities and explicit ignore-unfixed=false, with TRIVY_* shell overrides removed; no package exclusion or advisory suppression is used.

This coverage concerns Ubuntu OS packages, distinct from the required Maven advisory gate. It is not an analysis of application source or a complete vulnerability analysis of manually installed JDK binaries. CI retains raw OS inventory/findings and the separate scan summary; a failed advisory download is never recorded as a clean scan. Trivy's own version source is the [official release](https://github.com/aquasecurity/trivy/releases/tag/v0.76.1).
