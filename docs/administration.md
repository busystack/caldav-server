# Server administration

Complete recent primary and passkey verification, then select **Server
administration**. This area is distinct from your personal account. Administrator
status does not bypass private calendar permissions.

The overview shows the packaged application version, enabled/disabled account
counts, calendar/task-list and current resource counts, queried PostgreSQL/migration
versions, and observed HTTP errors. Database availability means its queries
succeeded for that request. Backup status is **Unknown** because backups are an
external operator responsibility. No automatic update check or telemetry runs.

## Accounts

Search and filter **Accounts** in 50-row pages. Select one for identity type,
status, role and display-name maintenance. Username, UUID, privileges and external
issuer/subject mappings cannot be edited here. Profile edits detect stale values.

**Create account** provisions an ordinary local account. Transfer the initial
password securely; the user must enroll/verify their own passkey before sensitive
changes. Organization accounts use the configured closed allowlist or protected
user identity linking. Provider roles do not grant application administration.

Disabling requires explicit confirmation, invalidates sessions and independently
issued client access, and retains stored data/membership. After the protected first-administrator setup, there is no browser
reactivation, password reset or further administrator creation. Use controlled offline
[bootstrap/recovery](operations.md) after independent identity verification.

## Groups

Search groups and page through members without loading the account directory.
Create an empty group, rename it, add an exact active username, remove an individual
member, or confirm group deletion. Single-member operations do not replace the
remaining membership set. Disabled memberships can be removed.

Before adding/removing a member, review the access-consequence explanation and
confirm it. Existing/future grants apply on addition; other direct/group grants
remain on removal. Group administration shows grant counts, not private collection
names or contents. Existing directory limits, mutation locks and V13 transactional
binding/synchronization reconciliation remain authoritative. See [groups](groups.md).

## Security audit and diagnostics

**Security & Audit** filters retained fixed action/outcome metadata and exact
account usernames in 50-row pages. Account and group administrative changes include opaque target UUIDs; membership
changes also identify the affected account UUID. Internal atomic membership
replacement records each actual addition/removal in a bounded batch; unchanged
members do not produce membership events. The actor UUID remains available
when its username no longer exists. Historical entries without targets are labelled
explicitly. Collection events do not expose collection identifiers or metadata.
It exposes no peer hashes, secrets, request bodies or calendar contents. The existing approximately 10,000-row / thirty-day
retention remains. New account/profile, collection lifecycle/import and direct-share
mutations use that same structured audit. Export reads do not add activity tracking.
OAuth client registration remains administrator-controlled.

**System & Diagnostics** shows configured deployment addresses, runtime/database
versions, applied migration and HTTP observations. A process-local buffer retains
up to fifty actual server failures with time, coarse surface, method, status and
server-generated request ID. It contains no resource paths, usernames or contents
and resets on restart. It is not durable monitoring or a backup-health assertion.

Use X-Request-ID to correlate safe operator logs. Database failure may prevent
session validation and access to administration itself; use local readiness probes
and deployment logs. Invalid configuration fails startup. Provider outage, expired
credentials, denied access and established CalDAV compatibility conditions have
contextual guidance. Infrastructure, SQL, files, configuration, certificates,
updates and backup/recovery remain under the deployment operator's control.
