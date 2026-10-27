# Measured workloads and operating limits

## Completion-pass measurements pending

No final performance or capacity result is yet claimed for the corrected source. The 270-resource measurements below belong to reviewed checkpoint `495989d`; they must not be extrapolated to the new paging/property/sharing implementation. Final source, image and measured outcomes will be linked from the maintained [qualification index](../qualification/evidence/README.md).

The required local HTTPS restore/workload entry point now combines the original independently generated event/task workload, actual packaged python-caldav and native headless Thunderbird round trips, and an extended boundary fixture. The ordinary application/Compose depth setting is omitted, with actual authenticated REPORT probes recording its effective behavior; explicit false has a separate real-HTTPS strict profile.

The extended fixture populates only owned disposable SQL namespaces with 10,037 independently generated valid calendar resources exceeding 32 MiB. Independent HTTPS requests traverse content and metadata in 97-row RFC 6578 pages, verify repeat/retry, updates/deletions/reinsertion/Unicode across cursor boundaries, restart the JVM and resume, then verify the subsequent delta and a selective query. Another phase uses 12 resources with repeated admitted 60 KiB property updates, small selected/name-only/all-property requests, three concurrent readers, deliberately oversized selected-response failure and a healthy request afterward. The real browser manages 12,500 group grants across 25 calendars and 500 groups, including first/last dashboard pages, revocation, membership replacement and deletion.

These fixtures measure bounded traversal and management, not SQL population or bulk HTTP create throughput. Docker memory/CPU samples, configured limits, actual owned-JVM MaxHeapSize/flags (or explicitly identified command/percentage evidence when attach is unavailable), request counts/durations, actual bytes and PostgreSQL before/after counters are retained in `target/qualification/workload.json` and `sharing-capacity.json`. The controlled restart has an explicitly recorded sampling gap. Observer failures fail qualification. Fresh OAuth access is obtained through the real public refresh endpoint after measurements, and recent primary plus WebAuthn authentication is repeated before protected browser revocation; token/MFA policy is not lengthened to accommodate testing.

The production limits remain explicit: one resource is at most 1 MiB; a dead-property target admits at most 1 MiB aggregate XML, 64 KiB per value and 1,000 names; XML construction has a 16 MiB conservative work and 50,000-node bound in addition to final serialized limits. These are implementation limits, not measured multi-user sizing. Historical resource/property versions increase durable storage and remain within the backup/privacy policy. The [sync algorithm](adr/0004-resumable-synchronization.md), [operating guidance](operations.md) and [conformance matrix](conformance.md) define the exact behavior.

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
