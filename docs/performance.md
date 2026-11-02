# Operating limits and workload scope

## Processing bounds

These results do not remove admission/work limits: a calendar representation is at most 1 MiB; one dead property is at most 64 KiB, with at most 1 MiB aggregate XML and 1,000 names per target. XML construction retains a conservative 16 MiB work and 50,000-node bound in addition to serialized limits. Synchronization pages have independent row, selected-response and retained-body limits rather than a total collection-size quota. The 97-row request used here deliberately exercises continuation often.

Group directory admission remains 1,000 groups, 1,000 accounts per group and 10,000 recorded memberships in total. Grant maintenance has no smaller hidden 10,000-grant listing ceiling; the dashboard uses 50-row pages, and membership/permission changes remain one atomic transaction with bounded lock retrieval and set-based reconciliation. The exact protocol/computation profile and supported namespace limits remain in [operations](operations.md), [groups](groups.md) and [conformance](conformance.md).


## Historical measurements

A synthetic packaged workload at source `47b3543674644d075ece97045088fc1e309e7d1e`
completed traversal of 10,037 resources (46,158,025 calendar bytes) in 104 content
pages and 104 metadata pages. It exercised continuation replay, concurrent
mutations, deletions, a JVM restart and a later incremental delta without fallback.
Separate property and sharing workloads exercised bounded reads and administrative
updates. These are historical results, not a v0.1.0 throughput or capacity claim.

The application container had a 1 GiB memory limit and the normal 70% maximum-RAM
heap policy on a shared Linux AMD64 host. End-to-end measurements included client
parsing, TLS, application and database work; SQL fixture population was not HTTP
ingestion. Full datasets, measurements and unsuccessful attempts remain in the
[evidence archive](../qualification/evidence/README.md#historical-records).

No service-level, multi-user scalability or high-availability guarantee follows.
Size the deployment using representative workloads, monitor database/storage
capacity and retain tested backups. The current CI correctness profile does not
rerun that historical performance workload.
