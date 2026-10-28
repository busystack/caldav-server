# Measured workloads and operating limits

## Current correction pass — measured phases passed

The packaged measurement phases passed on **2026-10-28 UTC** (2026-10-27 in Vancouver), against source `47b3543674644d075ece97045088fc1e309e7d1e`, application image `sha256:f24ea0e3092464dc243a3c8f4703bd579235faa6024c55772ce1f5fb7479a855` and executable JAR SHA-256 `4e97297d42102584da1ec78ac038362d2ed75eaf9874456ae4d4ec098c41aa6a`. The enclosing local restoration profile also passed, including restored old-credential/token/cookie denial, fresh owner/reader MFA, inherited property metadata, permissions, final restart and disabling. The separate final packaged federated restoration profile also passed against a real disposable Keycloak 26.8.1 instance; that security/recovery result is not inferred from these local measurements. [The qualification index](../qualification/evidence/README.md) records the separately completed source, deployment and recovery outcomes and links their exact evidence.

This is one synthetic sequential client workload on a shared host, with deliberately concurrent property readers and real browser sharing maintenance. No capacity, multi-user throughput, scalability or high-availability guarantee follows from it. SQL population is expressly not a measurement of HTTP bulk ingestion.

### Environment and effective configuration

* AMD A6-3400M APU, four logical CPUs, 11,449,860,096 bytes host RAM; Linux x86_64 `7.0.0-38-generic`, Docker `29.9.2`. Initial host load averages were 7.63/6.02/6.91; no other owned heavy qualification application ran during measurement, but unrelated host work and prior load remained.
* Temurin 25.0.4.1+1, Boot 4.1.2/Security 7.1.2 and PostgreSQL 18.6. Container memory limit **1 GiB**, no CPU quota. Actual owned-PID-1 `jcmd VM.flags` reported **752,877,568 bytes (718 MiB)** maximum heap and the normal `-XX:MaxRAMPercentage=70.0` command; no heap/work-limit override was applied.
* Independent Python 3.14.4, python-caldav 3.6.0, icalendar 7.3.0, and actual Thunderbird 157.0.1 native CalDAV provider with Marionette 3.7.1. Browser management used Playwright 1.61.1/Chromium 149.0.7827.55 with virtual UV-capable CTAP2 credentials.
* Normal isolated test-CA HTTPS through pinned Nginx under `/team`. The depth setting was **omitted**. Actual authenticated REPORT probes observed missing/Depth 0/Depth 1 = 207 and Depth 1 with another sync level = 400; synchronization fallback stayed disabled. The separate explicit-false strict profile is not inferred from those default results.

### Executed datasets and boundary results

| Phase | Actual result |
| --- | --- |
| Original independent-client workload | 200 nonrecurring events, 20 weekly recurring resources and 50 tasks (25 undated), 366,905 accepted bytes across two disposable collections. Ten month queries explicitly requested stored resources with `expand=False`. |
| Final packaged cross-client round trips | Both independent runners passed. Four identical resource URLs were edited Thunderbird→Python→Thunderbird→Python, retaining recurrence, one detached exception, EXDATE exclusion, all-day exclusive end, floating values, alarms, unknown properties and task completion timestamps; native expansion retained three occurrences. This is native headless desktop-provider evidence, not a manual account-wizard test. |
| Large content synchronization | **10,037 resources / 46,158,025 calendar bytes**, obtained completely in **104 pages** at a requested 97 rows per page. Continuation replay, updates, deletion/recreation, Unicode insertion across cursor boundaries and an actual JVM restart were verified without fallback. |
| Large metadata synchronization and delta | A separate initial metadata traversal completed in **104 pages**, without calendar-data properties. The subsequent incremental traversal returned the four expected changed/deleted URLs in one page. |
| Selective query over the large collection | HTTP 207 with exactly one matching resource, **36,830.94 ms** through the proxy, despite unrelated bodies exceeding the former aggregate limit. |
| Property storage/read/response stress | Twelve resources each accepted 17 individually valid **60,000-byte** large values plus a small selected property through repeated updates. Three concurrent readers received a small explicit property in at most **446 response bytes**, and names only in at most **1,384 bytes**. All properties for one target exceeded 1 MB and succeeded. Aggregate growth was rejected; a genuinely oversized combined selected-property response returned **507**, followed by a healthy GET. |
| Sharing administration | **12,500 grants**, 25 calendars and 500 groups. Actual CSRF-protected browser forms exercised dashboard page 0 and last page 249 (50 rows each), grant removal, group membership replacement and group deletion. SQL/binding assertions retained the effective reader access and removed the deleted group's writer bindings. Management/observation took **16.17 seconds**, excluding SQL population, with five Docker samples. |

The extended HTTP traversal/query/property phase completed in **258.47 seconds**, including its controlled JVM restart, with 462 requests, 58,995,075 response bytes and a largest individual response of 1,025,238 bytes. The composite original/native/extended measured phase completed in **339.18 seconds**. These phases have different scopes; neither duration is an HTTP ingestion benchmark.

### Explicit processing bounds

These results do not remove admission/work limits: a calendar representation is at most 1 MiB; one dead property is at most 64 KiB, with at most 1 MiB aggregate XML and 1,000 names per target. XML construction retains a conservative 16 MiB work and 50,000-node bound in addition to serialized limits. Synchronization pages have independent row, selected-response and retained-body limits rather than a total collection-size quota. The 97-row request used here deliberately exercises continuation often.

Group directory admission remains 1,000 groups, 1,000 accounts per group and 10,000 recorded memberships in total. Grant maintenance has no smaller hidden 10,000-grant listing ceiling; the dashboard uses 50-row pages, and membership/permission changes remain one atomic transaction with bounded lock retrieval and set-based reconciliation. The exact protocol/computation profile and supported namespace limits remain in [operations](operations.md), [groups](groups.md) and [conformance](conformance.md).

### Representative original-workload call latencies

These are end-to-end independent-client calls, including serialization, TLS/network, application/database and client parsing. Small sample counts and nearest-rank percentiles do not establish service-level guarantees. The large selective-query timing above is a separate single observation.

| Operation | Samples | Median ms | Observed p95 ms |
| --- | ---: | ---: | ---: |
| Create nonrecurring event | 200 | 84.44 | 186.77 |
| Create recurring resource | 20 | 52.62 | 64.63 |
| Create task | 50 | 43.61 | 65.37 |
| GET event | 20 | 22.03 | 31.89 |
| Month resource query | 10 | 1917.74 | 2732.13 |
| Update event | 10 | 43.20 | 50.29 |
| Delete event | 5 | 37.17 | 43.05 |
| Initial sync call | 1 | 1319.16 | 1319.16 |
| Incremental sync call | 1 | 114.16 | 114.16 |

### Memory and database observations

The composite phase collected **94 Docker samples**, with sampled peak application memory **365.5 MiB / 1 GiB** and maximum sampled CPU **225.91%** (about 2.26 logical CPUs). Sampling intentionally pauses during the controlled JVM restart and resumes after authenticated readiness; that gap is explicit. There were no observer errors. Sampled process/container memory is distinct from actual maximum JVM heap, and sampling can miss shorter peaks.

Database allocation changed from 13,096,639 to 33,248,959 bytes, and the resource relation including indexes/TOAST from 49,152 to 6,717,440 bytes. Commits increased by 5,699, disk blocks read by eight and buffer hits by 1,056,038. Rollbacks increased by five while deliberately rejected requests were exercised. No new temporary files, deadlocks or checksum failures were reported. Statistics are asynchronous and include authentication, probes, fixture population and cleanup; they are not exact per-operation costs. PostgreSQL compression and retained relation allocation mean logical accepted bytes, database file size and live row count are different quantities.

Deleting these generated collections does not model years of retained edits. Immutable historical bodies and normalized properties have the same privacy/backup obligations as current data, and history retention remains indefinite by default. Monitor vacuum, dead tuples, history and disk growth.

### Separate quiet query/free-busy observation and deadlines

The same final image and 10,037-resource/46,158,025-byte dataset were also exercised through embedded trusted-CA HTTPS, without a proxy or another owned application active. A cold selective calendar-query returned one entry in **35.088 seconds** (439 response bytes); free-busy returned one independently parsed VFREEBUSY component/period in **28.206 seconds** (317 bytes). Actual maximum heap was again 752,877,568 bytes; cgroup-v2 memory peak was 285,282,304 bytes. This is a separate fixture, not an extra sample silently folded into the proxy workload. Unrelated host load and observation overhead remained; cleanup was verified.

The former 30-second proxy read deadline was therefore insufficient for this measured valid bounded query. The qualification proxy and deployment example use **120 seconds** for reads while retaining **5 seconds** for connect. Only the extended selective-query client call uses **150 seconds**; its other calls keep **60 seconds** and its process bound stays **900 seconds**. Computation/response limits, token expiry and recent-MFA windows are unchanged. The retained OAuth grant is refreshed through the actual public token endpoint after measurements; primary authentication plus WebAuthn is repeated before browser revocation, and live-at-backup cookie/old-credential rejection remains part of restoration qualification.

Reproduce with the required `--proxy --groups --restore --workload` entry point and locked native/Python prerequisites in [browser qualification](../qualification/passkeys/README.md). Measurements, exact image/configuration, client results and database counters are recorded in `workload.json`, with separate `sharing-capacity.json` and quiet query/free-busy evidence linked by the qualification index. The documented limits describe bounded processing, not an unmeasured total-user or collection-capacity promise.

## Historical measurement — reviewed `495989d` checkpoint

On 2026-10-27, the final packaged application completed a bounded independent-client workload and the local HTTPS backup/restore profile. [The full measurement record](../qualification/evidence/workload-final-date.json) includes the exact image, environment, dataset, client versions, container samples and PostgreSQL counters. This is one sequential client on a shared host, not a capacity, concurrency, scalability or high-availability result.

### Environment and dataset

* Application image: `sha256:9d458a08d84255cd174e3d64a597b794c8a3eaa621b6c050a1fbcee4c798a287`; executable JAR SHA256: `b6cfa0332fce0c2b0b60339b643c308a0137b828d82253a418eeb8ce911d01ac`.
* Host: AMD A6-3400M, four logical CPUs, 11,449,860,096 bytes RAM; Linux x86_64 kernel 7.0.0-38-generic; Docker 29.9.2. Initial load averages were 3.24/2.31/2.98. Other host work was present.
* Java 25.0.4.1+1, Spring Boot 4.1.2/Security 7.1.2 and PostgreSQL 18.6. The application had a 1 GiB memory limit and no CPU quota.
* Independent Python 3.14.4 client: python-caldav 3.6.0 with icalendar 7.3.0; normal test-CA HTTPS through pinned Nginx 1.30.6, with the explicit sync Depth 1 compatibility profile and synchronization fallback disabled.
* Two disposable collections: 200 nonrecurring events, 20 weekly recurring resources with 12 occurrences each, and 50 tasks, including 25 undated tasks. Accepted representations totalled 366,905 bytes. Queries returned 220 stored event resources, explicitly using `expand=False`; they do not measure client-side or server-side report expansion. Separate protocol/semantic suites qualify calendar-data expansion.

The client imported the dataset, read 20 events, made ten month-range queries, performed initial sync, updated ten events, deleted five, verified incremental deletions and counts, and removed only its own two generated calendars. Total observed elapsed time was 54.63 seconds, including setup/checks/cleanup and observation overhead.

### Observed client-call latencies

Times include independent-client serialization/parsing, TLS/network, application and database work. They are not isolated server execution times. Percentiles are nearest-rank observations from the small sample sizes shown, without confidence or service-level guarantees. Sync figures time the library calls; their cardinality/deletion assertions run separately within the total workload.

| Operation | Samples | Median ms | Observed p95 ms |
| --- | ---: | ---: | ---: |
| Create nonrecurring event |200 |54.39 |85.29 |
| Create recurring resource |20 |48.44 |58.05 |
| Create task |50 |44.05 |55.75 |
| GET event |20 |21.98 |27.64 |
| Month resource query |10 |2768.17 |3993.66 |
| Update event |10 |45.52 |86.60 |
| Delete event |5 |34.65 |86.79 |
| Initial sync call |1 |945.57 |— |
| Incremental sync call |1 |95.56 |— |

Month queries in this dataset took seconds; ordinary GET/write calls took tens of milliseconds. These measurements must not be presented as low-latency query or large-calendar throughput claims. Measure the intended data and client mix before choosing production sizing or timeouts.

### Memory and database observations

Nineteen Docker samples, approximately three seconds apart, observed application memory from 261.9 MiB to 357.5 MiB under its 1 GiB limit. The largest sampled CPU value was 224.34% (approximately 2.24 logical cores in Docker's accounting). Sampling can miss shorter peaks; browser/proxy/PostgreSQL memory and observer/client CPU are separate from the application sample.

PostgreSQL database allocation increased from 10,032,831 to 10,745,535 bytes; the resource relation allocation increased from 49,152 to 589,824 bytes. Removing objects does not immediately shrink PostgreSQL relation files. Reported commits increased by 1475; disk blocks read increased by 12 and buffer hits by 72,607. Rollbacks remained 2, and no new temporary files, deadlocks or checksum failures were observed. Counters include background health/authentication work and update asynchronously, so they are not exact per-operation costs.

The documented default history retention remains indefinite. Monitor storage, dead tuples, vacuum and history growth; the workload's deletion of its entire disposable collections does not model years of retained edits. See [operations and fixed processing limits](operations.md#health-diagnostics-and-limits).

### Reproduction and earlier attempt

Install the locked client environment, build the pinned application/browser fixtures, then run the local restore/workload profile described in [browser qualification](../qualification/passkeys/README.md). It obtains a fresh named DAV credential through real browser MFA and keeps its secret only in a private temporary file. Missing prerequisites, workload/observer failures or token expiry fail visibly.

The first attempt's [measurement](../qualification/evidence/workload-final-date-attempt1.json) also passed the workload in 55.37 seconds, but its subsequent restore browser flow stopped on a fixture assertion that did not accept Spring's legitimate `?continue` saved-request marker. [That failed restore attempt](../qualification/evidence/restore-local-final-date-attempt1.txt) is retained. The corrected, strictly scoped URL assertion was then exercised by [the completed local HTTPS restore profile](../qualification/evidence/restore-local-final-date.txt); it did not require a production change. Results from a failed overall profile are not relabeled as a completed restore.
