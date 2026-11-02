# Portal implementation assessment

Starting checkout: `release/v0.2.0`, `db302ec69f786ba89945e1a761734a844572145b`.
The existing uncommitted application-version change to 0.2.0 is preserved.
Development uses `feature/v0.2.0-portals`; no changes are made to main or published
migrations. No AGENTS.md instructions were present in the checkout or ancestors.
The release procedure remains authoritative for integration and publication.

## Existing contracts

Spring MVC/Thymeleaf renders twenty small templates. ManagementController and
ManagementService expose collection creation, individual sharing, DAV credential
issuance/revocation and ordinary account provisioning/disabling. GroupService
already enforces administrator membership management separately from owner grants.
OperationalDiagnostics exposes aggregate metadata; SecurityAudit already stores
bounded structured events. Login and passkeys lacked the common stylesheet.

CalendarStore is authoritative for collection identity, resource admission, UID
uniqueness, revisions and synchronization. DavStore owns bindings and atomic DAV
property changes/deletion. `collection_effective_members` combines direct/group
READ/WRITE permissions for DAV authorization. V13 reconciles group bindings under
the existing mutation locks. Metadata changes must retain paths, types and bodies.

V1–V13 are forward-only Flyway migrations. Account UUIDs, enabled state, security
epochs and SessionSecurity checks remain authoritative. Sensitive mutations require
the existing ten-minute primary and WebAuthn factors. External identities retain
issuer/subject mappings, closed allowlisting, local factors and bounded freshness.
Offline bootstrap/recovery, OAuth, session and credential revocation are retained.

## Gaps and implementation boundaries

Add coherent personal/admin navigation; bounded metadata screens; collection
editing/deletion confirmation; per-collection grants and effective permissions;
connection instructions and security organization; structured paginated account,
group and audit administration; contextual setup; and validated iCalendar
preview/confirmation/export. Reuse services rather than dispatching browser HTTP
to DAV or maintaining another collection/permission model. No event/task editor,
public sharing, registration, email or privileged browser recovery is introduced.

Use standard CalDAV calendar-description and the existing dead-property mechanism
for the Apple calendar-color convention. Client acceptance must be measured; a
stored property alone is not proof of a client's UI behavior.

The required PostgreSQL/HTTPS integration tests, real Keycloak fixture, independent
Python/Thunderbird clients, packaged Chromium WebAuthn/proxy/restore harness and
distribution checks already exist. Extend these. Historical evidence qualifies
only its recorded artifacts. Current qualification results are recorded separately.

Reference review: Baïkal 0.13.0 administration and Radicale 3.9.0 collection,
metadata, share, upload and deletion workflows inform the workflow structure only.
No competitor source or modified third-party implementation is incorporated.
