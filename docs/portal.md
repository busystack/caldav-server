# Your BusyStack account

BusyStack manages your account, collections and client access. Edit actual events
and tasks in your CalDAV application. Local authentication requires no identity
provider, email service or vendor account.

## First sign-in

Open your operator's configured HTTPS address. Verify your local password, or use
the configured organization sign-in. Enroll your first passkey under **Security →
Passkeys**, then verify that passkey to complete MFA. Registration alone does not
complete authentication. Sensitive changes require primary sign-in and passkey
verification within ten minutes. The overview and security pages explain which
step is needed. Renew both factors for the same account when they expire.

The overview adapts to actual passkeys, owned/shared collections, unexpired DAV
credentials and unused recovery codes. It never claims that a device is connected.
Save recovery codes offline; see [account security](account-security.md). There is
no public registration, email recovery or browser-based operator recovery.

## Calendars and task lists

Open **Calendars & Tasks**. **My calendars and task lists** contains your owned
collections; **Shared with me** contains direct/group shared collections. Search
and page through names. Select a collection for its details and address.

Choose **Create a collection**, supply a name, choose Calendar or Task list and
optionally enter a description and colour. Types stay fixed. Metadata changes
preserve the collection's identity, address and stored resources. Descriptions use
CalDAV `calendar-description`; colour uses the Apple `calendar-color` convention,
with #RRGGBB or #RRGGBBAA values. This is not a universal client-colour guarantee.
A client may maintain its own local colour preference.

Read/write recipients can change shared metadata and import contents according to
the existing DAV permissions. Only owners can manage sharing or delete a
collection. Deletion has a separate review page and requires server-checked
confirmation of the current state. It removes resources, history, bindings and
sharing grants. Export before deletion when a migration copy is needed; operator
backups are separate. If another request changes the target, reload and review the
new state instead of retrying a stale form.

## Sharing and effective access

Open **Manage sharing** on an owned collection. Add a direct grant using the
recipient's exact username; no public account directory or autocomplete is
provided. Grant read-only or read and write, change a recorded grant, or remove it.
Group grants apply to every current and future member. Administrators maintain
membership, so granting a group delegates that membership decision.

The effective-permission table uses the same `collection_effective_members` view
as DAV authorization. Any read/write grant supplies read/write access. Removing a
direct grant does not remove access provided by a group; removing one group grant
does not remove another grant. Disabled accounts cannot authenticate, even if
membership remains recorded. Sharing changes can invalidate synchronization tokens;
clients must perform initial synchronization when a token is rejected.

## Connect an application

Open **Connections**, then issue a named credential under **Security → DAV
credentials**. Copy its once-shown secret directly into the application's password
settings. The account password is not the DAV password. Secrets cannot be retrieved
later. Credential labels, creation/expiration and active/expired/revoked status are
available without displaying verifiers. To replace one, issue a replacement,
configure the client, then revoke the old credential.

Copy the displayed server address, username and, when needed, collection address.
Addresses include the operator's configured subpath and never depend on request
Host or forwarding headers. Use normal TLS verification.

Thunderbird instructions on Connections follow the documented network-calendar
wizard. Historical qualification covers Thunderbird 159.0 and GNOME Calendar 50.0 /
EDS 3.56.2 on the exact 0.1.0 executable and configurations in the
[client record](observance-until-compatibility.md#qualified-client-configurations).
The historical GNOME record uses a source provisioned through EDS's supported API.
Connections also documents the GNOME 50.0 wizard: **Manage Calendars → Add Calendar**,
enter the collection HTTPS address under **Connect to an Online Calendar**, supply
the username and dedicated DAV credential in **Credentials**, choose **Connect**,
select the discovered calendar and choose **Add Calendar**. Those graphical setup
steps are documented but await qualification. Current artifact results are recorded separately; BusyMax
and other untested clients are not advertised as supported.

Registered OAuth applications may use browser consent instead of a DAV credential.
Review them under **Security → OAuth applications**; see [OAuth](oauth.md).

## Security and data portability

Security links to passkeys, credentials, browser sessions, recovery and OAuth
grants. Local accounts can change their password. Organization-managed accounts
change primary credentials at their identity provider; there is no local-password
fallback. Local passkeys, credentials and permissions still belong to BusyStack.
Provider outages/freshness retain the [existing identity policy](keycloak.md).

Use **Import & Export** for validated, confirmed iCalendar migration. See
[portability](portability.md) for conflict handling, limits and representation.
