# Server administration

Complete browser sign-in, then select **Server administration**. Ordinary
administrative reads remain available in a completed browser session. Protected
actions prompt for recent verification. Resource owners control private access.

The overview shows enabled and disabled account counts, groups, calendars,
task lists, journals and stored UID series. **System & diagnostics** shows
versions, the applied schema and HTTP observations.

## Accounts

Search **Accounts** in 50-row pages. Combine Status and Sign-in method filters. Select one for identity type,
status, role and display-name maintenance. Username, UUID, privileges and external
issuer/subject mappings cannot be edited here. Profile edits detect stale values.

**Create account** creates a local account. Copy the nonsecret sign-in instructions from the new account’s detail page. Share the initial password separately through a trusted channel. The user registers and verifies their own passkey. Organization accounts use the configured closed allowlist or protected
user identity linking. Provider roles do not grant application administration.

Disabling requires confirmation, ends sessions, revokes client access and retains stored data and membership. Browser administration prevents self-disable and lockout of the last enabled administrator. After the protected first-administrator setup, there is no browser
reactivation, password reset or further administrator creation. Use controlled offline
[bootstrap/recovery](operations.md) after independent identity verification.

## Groups

Search groups and page through members. Use the bounded administrator account lookup to find a new member.
Create an empty group, rename it, add an exact active username, remove an individual
member, or confirm group deletion. Single-member operations do not replace the
remaining membership set. Disabled memberships can be removed.

Review how membership affects current and future grants before adding or removing members. Existing/future grants apply on addition; other direct/group grants
remain on removal. Group administration shows grant counts, not private collection
names or contents. Existing directory limits, mutation locks and V13 transactional
binding/synchronization reconciliation remain authoritative. See [groups](groups.md).

## Security audit and diagnostics

**Security & audit** filters retained fixed action/outcome metadata and exact
account usernames in 50-row pages. Administrative targets show current authorized directory labels. Recorded references retain the opaque target and affected-account UUIDs. Deleted or unavailable targets keep their IDs. Internal atomic membership
replacement records each actual addition/removal in a bounded batch; unchanged
members do not produce membership events. The actor UUID remains available
when its username no longer exists. Historical entries without targets are labelled
explicitly. Collection events do not expose collection identifiers or metadata.
It exposes no peer hashes, secrets, request bodies or calendar contents. The existing approximately 10,000-row / thirty-day
retention remains. New account/profile, collection lifecycle/import and direct-share
mutations use that same structured audit. Export reads do not add activity tracking.
OAuth client registration remains administrator-controlled.

**System & diagnostics** shows configured deployment addresses, runtime/database
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
