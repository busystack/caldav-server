# Getting started with BusyStack CalDAV Server

This is the complete operator, administrator and user guide for BusyStack 0.2.0.
Release packages replace `@VERSION@` below with their exact version. When reading
this file in the source repository, substitute `0.2.0` for that placeholder.
Only download a version whose official release assets are available.

## 1 — What BusyStack does

BusyStack stores calendars, task lists and journals on a server you control. CalDAV is the
protocol that compatible calendar applications use to discover and synchronize
those collections. Edit events, tasks and journal entries in a connected application, such as
Thunderbird. The web portal manages accounts, collections, sharing, application
access, security, and iCalendar import/export; item editing happens in the connected app.

Standalone operation needs no Keycloak, email delivery, cloud service, vendor
account, telemetry, analytics, licensing server, or remote frontend assets.
Your database and backups contain your data; control access to both.

## 2 — Requirements

The release container distribution supports **Linux AMD64**, Docker Engine, and
**Docker Compose 2.24.4 or newer**. Other operating systems and architectures are
not qualified by these instructions. Check the host before downloading:

```sh
uname -s -m
docker version
docker compose version
```

Expect `Linux x86_64`, a responding Docker server, and an adequate Compose version.
Your operator account needs access to Docker; that access controls this server.
The command examples also use a shell, curl, tar, sha256sum, OpenSSL and standard
Linux file utilities. No host Java, Maven, source checkout or compilation is
needed. The image supplies Java 25, Spring Boot and the application. Compose
supplies private PostgreSQL 18.6.

Provide persistent disk space for PostgreSQL, growing calendar/synchronization
history and separate backups. No measured minimum host RAM/disk specification is
published. The application container has a 1 GiB memory limit and a 384 MiB `/tmp`
allocation; those defaults are not a whole-host capacity guarantee. PostgreSQL,
Docker, the proxy and backups need additional resources. Monitor usage.

Provide a stable hostname, appropriate DNS, and a trusted HTTPS certificate and
private key matching that hostname. A private deployment may use its own CA only
when every client trusts that CA normally. The package does **not** obtain public
certificates automatically. Supply and maintain the HTTPS reverse proxy yourself;
working Nginx configurations are included. Certificate issuance, DNS and firewall
administration remain operator responsibilities.

Allow HTTPS from intended clients to the proxy. Allow outbound access needed to
download release assets and pinned images. Do not expose PostgreSQL or the internal
HTTP listener publicly. The supplied Compose binds application HTTP to loopback
and publishes no PostgreSQL port. Use persistent volumes, protected configuration,
and secure local operator access. Keep time correct for TLS, expiry and passkeys.

## 3 — Download and verify

Choose an official version. In a new directory, run:

```sh
VERSION=@VERSION@
curl -fLO "https://github.com/busystack/caldav-server/releases/download/v${VERSION}/caldav-server-${VERSION}-deploy.tar.gz"
curl -fLO "https://github.com/busystack/caldav-server/releases/download/v${VERSION}/SHA256SUMS"
curl -fLO "https://github.com/busystack/caldav-server/releases/download/v${VERSION}/release-manifest.json"
sha256sum --check --ignore-missing SHA256SUMS
tar -xzf "caldav-server-${VERSION}-deploy.tar.gz"
cd "caldav-server-${VERSION}-deploy"
```

Continue only when the archive and `release-manifest.json` report `OK`, with no
checksum failure. Other assets not downloaded are skipped. Checksums detect
corruption; obtain them from the official immutable release, whose attestation
binds the publisher's assets. The manifest records source, JAR checksum, tested
image identity and qualification evidence. Do not run unverified remote scripts.

All remaining deployment commands run **inside this extracted directory** unless
stated otherwise. It contains `INSTALL.md`, this guide and screenshots, Compose,
proxy examples and scripts. Its Compose pulls the published version **and immutable
registry digest**. Preserve that identity; do not substitute `latest` or a locally
built image for ordinary installation.

## 4 — Configure the server

```sh
cp .env.example .env
install -d -m 700 "$HOME/.config/busystack-caldav"
(umask 077; openssl rand -base64 48 > "$HOME/.config/busystack-caldav/database-password")
(umask 077; openssl rand -base64 48 > "$HOME/.config/busystack-caldav/postgres-admin-password")
chmod 444 "$HOME/.config/busystack-caldav/database-password" "$HOME/.config/busystack-caldav/postgres-admin-password"
```

Use new secret files for a new installation; do not overwrite secrets belonging
to an existing deployment. OpenSSL generates the two independent passwords.
Never paste them into `.env`, process arguments, browser pages, Git or logs.
The containing directory stays mode 0700; file mode 0444 lets the non-root
container users read bind-mounted Compose secrets without exposing them to other
host users through that private directory. Do not make the directory public.
Protect backups of configuration and secrets separately from database backups.

Edit `.env` with your values. For a hostname-root installation:

```dotenv
BUSYSTACK_PUBLIC_BASE_URL=https://calendar.example.org
BUSYSTACK_ISSUER=https://calendar.example.org
BUSYSTACK_DATABASE_PASSWORD_FILE=/home/OPERATOR/.config/busystack-caldav/database-password
BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=/home/OPERATOR/.config/busystack-caldav/postgres-admin-password
BUSYSTACK_HTTP_PORT=8080
```

Replace the hostname and both absolute paths. Shell expressions such as `$HOME`
are not a substitute for writing the intended absolute paths here. Public URL
and OAuth issuer must be equal, without trailing slash, query or fragment. These
are externally visible HTTPS addresses, not container network addresses.

For a subpath, set **both** URLs to `https://calendar.example.org/calendar` and use
`nginx-subpath.conf.example`. The application derives its servlet prefix from this
configuration. The proxy must preserve `/calendar`; it must not strip the prefix.
It must also handle host-root discovery using the supplied explicit mappings.
Use canonical ASCII prefixes. Choose the hostname deliberately: changing it can
make previously registered passkeys unusable.

Use [the complete root proxy example](../deployment/nginx-root.conf.example) or
[the complete subpath proxy example](../deployment/nginx-subpath.conf.example) in an extracted
package. In the source repository these examples are in `deployment/`. Replace
`calendar.example.org`, certificate paths and, if changed, loopback upstream port.
Install the chosen example through your existing Nginx administration procedure;
run `sudo nginx -t` before reloading it. Both examples restrict health endpoints,
disable compression, and supply the larger bounded import route. Do not enable
blanket forwarded-header trust. [Operations](operations.md#https-and-proxy-paths)
explains the configuration and other operator considerations.

PostgreSQL data lives in the Compose `database` volume at `/var/lib/postgresql`.
Initialization scripts only run on an empty volume. Changing a secret file later
does not change an existing database role password; rotate the role and file
together during controlled maintenance.

For an isolated trial, set a new `COMPOSE_PROJECT_NAME` and unused
`BUSYSTACK_HTTP_PORT` in `.env` before the first Compose command. Point the trial's
proxy at that port. Never use a production project's volume for a trial or restore.

## 5 — Start BusyStack

```sh
docker compose config --quiet
docker compose pull --policy missing
docker compose up -d --no-build --wait --wait-timeout 240
docker compose ps
```

Configuration validation must exit successfully. Pull must obtain the configured
pinned images. PostgreSQL becomes healthy before the application starts; application
readiness includes database connectivity. Both services should show healthy.

Check actual HTTPS, including the configured subpath if any:

```sh
curl --fail --show-error --silent --location --output /dev/null https://calendar.example.org/
curl --head https://calendar.example.org/.well-known/caldav
```

For a `/calendar` deployment, use `https://calendar.example.org/calendar/` in the
first command. Discovery should redirect to the configured `/calendar/dav/`.
Check certificate validity and hostname with normal validation; do not use `-k`.
Open the public URL in a browser. A fresh database must show **Set up BusyStack**.
A running container alone does not prove DNS, HTTPS, proxy paths or isolation.

Inspect listeners with your host's network tools, for example `ss -ltn`.
The application port should be `127.0.0.1:8080` (or your selected loopback port),
with no published database port. Check the host firewall from a permitted client.
Use `docker compose logs --tail 100 application database` for startup diagnostics;
keep diagnostic output private and never enable request-body/credential logging.
See chapter 19 if startup or HTTPS fails.

## 6 — Create the first administrator

![Protected setup form with empty initialization code and password fields](images/getting-started/01-protected-setup.png)

Open the configured URL. **Set up BusyStack** confirms that the server is running
and needs its first administrator. On the server, in the extracted deployment
directory containing `compose.yaml`, run:

```sh
bash scripts/initialize.sh
```

The command runs a one-off Compose container and prints a code once to the
operator's terminal. The code lasts **15 minutes**. Keep it private. Run the same
command to replace an expired code or unlock setup after five invalid attempts.
A replacement invalidates the previous code. **Setup and recovery help** links
to the local help page.

Enter the code, username, password and confirmation on the same setup page.
Usernames use 1–64 ASCII letters, digits, dots, underscores or hyphens and must
start with a letter or digit. Passwords need at least 14 Unicode characters and
at most 72 UTF-8 bytes. A password manager can fill the fields. If the entries
differ, correct them in the form. After server validation errors, enter any
required code and password again.

Select **Create administrator account**. BusyStack opens normal sign-in with a
confirmation and the username. The account is created without a browser session or
passkey. Setup stays closed for this installation after restart, upgrade, account
disabling and restore. Existing installations use normal sign-in. Offline
operator recovery is described in chapter 16.

## 7 — Sign in with a passkey

![Sign-in showing the confirmation that the first administrator was created](images/getting-started/02-sign-in.png)

Sign in with the account's password or use the organization sign-in link if your
operator configured it. Organization-managed accounts use the provider for their
primary sign-in and a BusyStack passkey for verification.

![First passkey page with a Create passkey button and no portal navigation](images/getting-started/03-create-passkey.png)

Select **Create passkey**. Follow the browser's prompt using an available password
manager, device unlock, phone or security key. Options depend on your browser,
device and provider.

![Saved passkey with the Verify and continue action](images/getting-started/04-passkey-saved.png)

When **Passkey saved** appears, select **Verify and continue**, then use the
passkey in the native prompt. BusyStack opens the authorized task you started from,
or **Overview**. Registration and verification use separate browser prompts.
Returning users start with **Verify and continue** after primary sign-in.

![Returning-user verification with recovery available](images/getting-started/26-returning-verification.png)

If a prompt closes, review the status and request a new prompt when ready. If the
connection drops after creating a passkey, return to the page so BusyStack can
check whether it was saved. Reopen sign-in if the session ended. If you lost your
passkey, select **Use a saved recovery code** or contact the operator.

A completed browser session expires after 30 minutes of inactivity or at its
eight-hour overall limit. Sensitive actions, including private imports and
exports, require both factors to be under ten minutes old. When asked to confirm
your identity, follow the prompt, return to the task and submit it again. If the
draft needs a password, code or file, enter or select it again. Use **Stay signed
in** for an approaching idle limit, or sign in again for the overall limit.

## 8 — Choose what to do from Overview

![Administrator Overview with personal setup and server administration choices](images/getting-started/05-overview.png)

Use **Create a resource** for personal setup or **Import existing data** to select
a destination. To administer the server, use **Open server administration**.
You can do this before creating a personal resource. Recovery codes are optional
preparation available through Security.

![Server administration overview with account and resource totals](images/getting-started/06-server-administration.png)

**Server administration** has separate Accounts, Groups, Security & audit and
System & diagnostics sections. Administration shows account and operational
metadata. Owners grant access to private resources separately.

## 9 — Create and manage user accounts

![Account directory with separate status and sign-in method filters](images/getting-started/07-accounts.png)

Open **Server administration → Accounts → Create account**. You can combine
status and sign-in method filters when searching the directory.

![Create account form with username, display name and initial password](images/getting-started/08-create-account.png)

Choose the immutable username, editable display name and an initial password
meeting the setup policy. Select **Create account**.

![New account details showing the nonsecret handoff instructions](images/getting-started/09-account-handoff.png)

On the account page, use **Copy sign-in instructions** to share the configured URL
and username. Give the initial password separately through a trusted channel.
The recipient registers and verifies their own passkey. They can change the
initial password in Security.

To disable another account, open its details and review **Disable account**.
Confirming ends sessions and revokes client access while retaining stored data
and membership. Recovery is an offline operator task. Administrators cannot
disable their own account from the browser.

## 10 — Use resources shared with you

![User Overview showing a shared calendar and the Connect an app action](images/getting-started/25-shared-overview.png)

Open the URL supplied by the administrator, sign in and finish the passkey steps
from chapter 7. **Overview** shows the resources you can use and their access
levels. You can connect an app before creating anything. If someone needs to share
a resource with you, give them your exact username.

## 11 — Calendars, task lists and journals

![Resource form with Calendar, Task list and Journal choices](images/getting-started/10-resource-type.png)

Open **Calendars, tasks & journals → Create a resource**. Select **Calendar** for
events, **Task list** for tasks, or **Journal** for journal entries. Enter a name.
Description and color are optional. A color can be six hex digits or eight if it
includes opacity. The type is fixed after creation.

![Empty task list explaining how to add tasks and why there is no export](images/getting-started/11-empty-task-list.png)

On a resource's detail page, use **Connect an app** or **Import an iCalendar
file** to start adding content. **Settings** is available to owners and read/write
recipients. Changing metadata leaves the client address and contents intact.
Read-only recipients can read details and export available content.

![All resources with calendars, task lists and journals](images/getting-started/12-resources.png)

Use **All**, **Owned by me** or **Shared with me** and search by name to find
resources. Select the resource name to open its details. Owners can review
deletion there. A confirmed deletion removes contents, synchronization history,
bindings and grants. Export or obtain a backup before deleting data you need.

![A calendar with direct access and the resulting effective permissions](images/getting-started/13-sharing.png)

Owners open **Manage sharing** and choose an account by exact username, or an
available group. **Read-only** allows reading and export. **Read and write**
also allows permitted content and metadata changes and import. The owner alone
manages sharing or deletes the resource. Any read/write grant makes read/write
access effective. Removing one grant leaves other grants in effect.

Administrators create and manage groups from **Server administration → Groups**.
Open the group, find or enter the member's account, and review how many current
grants apply. Future grants follow membership. Group deletion removes memberships
and grants while retaining underlying resources. See [Groups](groups.md).

## 12 — Connect an app

![App selection showing Thunderbird, GNOME Calendar and Generic CalDAV](images/getting-started/16-connect-an-app.png)

Open **Connect an app** and choose your app. The server address includes any
configured subpath. Use the exact address, username and a separate application
password. Supported registered apps may use browser authorization.

![GNOME Calendar settings with the configured username-bearing address](images/getting-started/17-client-configuration.png)

Choose **I already have an application password** to use existing settings, or
**Create application password**. Enter a label and choose a lifetime between
1 and 365 days. The form defaults to 90 days.

![Application password form showing the label and expiration period](images/getting-started/18-create-application-password.png)

Keep the one-time result open while configuring your app. **Copy password**
copies the secret. If your browser prevents copying, select the text manually.
Use the settings and instructions on the same page. Select **Done** when ready.

![Application password result with the secret masked and setup steps on the same page](images/getting-started/19-password-created.png)

If you lose the password, review existing credentials and deliberately issue a
replacement. BusyStack cannot display an issued password again. Configure your
app with the replacement before revoking its previous password. Verify actual
synchronization in the app with a synthetic item.

### Thunderbird

Version-scoped existing evidence covers Thunderbird 159.0 graphical workflows and
157.0.1 native-provider tests; final engineering evidence records which profiles
were rerun against this implementation. To configure:

1. Open Calendar and choose **New Calendar → On the Network**.
2. Enter the displayed username and server address as **Location**. Keep
   **This location doesn't require credentials** unchecked. Choose **Find Calendars**.
3. Enter the **application password**, not your primary BusyStack password, when
   prompted. Optionally retain it in Thunderbird's Password Manager.
4. Select CalDAV and the discovered calendars/task lists, then **Subscribe**.
   Use Thunderbird's task view for VTODO task lists.

Create a synthetic event and task, refresh, edit them, complete a task, and verify
changes from another authorized client/profile. Test deletion only on synthetic
data. After an application restart, confirm the data remains synchronized.

### GNOME Calendar / Evolution Data Server

Existing version-scoped evidence covers Calendar **50.0** / EDS **3.56.2**. Use the
portal's **GNOME connection address**, which includes your username but no password;
this wizard needs it to retain the authentication user after discovery.

1. Choose **Manage Calendars → Add Calendar**.
2. Paste that address under **Connect to an Online Calendar**.
3. In **Credentials**, enter your username in **User** and your application password
   in **Password**, then **Connect**.
4. After validation, choose **Add Calendar**. If synchronization prompts again,
   enter the same application password. Refresh and check a synthetic event.

GNOME Calendar has no task editor. Do not infer a task workflow from event discovery.
Existing EDS qualification is distinct from unqualified Evolution graphical setup.

### Generic CalDAV

Choose CalDAV/password authentication over HTTPS. Use the displayed server address,
username and application password. Discovery should show owned/shared collections.
If the application requires a collection URL, copy the appropriate address from
Connect an app. Choose a client that supports the resource types you need. Task and journal handling and wizard labels vary by app.

Use **Security → Application passwords** to review expiration/revocation metadata.
To replace a password, create a new one, update the client, then revoke the old one.
Revocation denies future authentication; a request already authorized may finish.
Registered OAuth-capable clients may instead use browser authorization with consent;
ordinary CalDAV discovery does not provide OAuth support automatically.

## 13 — Security and administrative audit

![Security overview with six account security topics](images/getting-started/14-security.png)

Open **Security** to review passkeys, application passwords, sessions, recovery
codes, authorized applications and password or organization sign-in.

- **Passkeys** shows each name and recorded creation and last-used time. Add a
  replacement before removing your last passkey. Removing a passkey ends the
  assurance of sessions verified with that key. After removing the last passkey,
  create and verify a new one to resume browser access.
- **Application passwords** shows expiry and revocation. A revoked password can
  no longer authenticate a new client request.
- **Browser sessions** distinguishes **This browser** from other sessions. You
  can end one after verification. Ending the current browser session signs you out.
- **Authorized applications** shows browser-approved app grants. Revoke one to
  remove that grant. An app can ask you to authorize again.
- **Password and organization sign-in** lets local accounts change the password.
  A local password change retains passkeys and invalidates existing sessions,
  recovery codes and client authorization. For a managed account, change primary
  credentials with your organization. Linking an organization identity replaces
  local-password sign-in and revokes affected access while retaining passkeys and
  resource identity. See [Account security](account-security.md) and [OAuth](oauth.md).

![Recovery-code result with all ten secret values masked](images/getting-started/15-recovery-codes.png)

Under **Recovery**, generate and save ten single-use codes offline. Codes expire
after a year and appear once. Replacing the set invalidates its unused codes.
To recover from a lost passkey, first sign in with your password or organization
provider, then select **Use a saved recovery code**. Recovery consumes one code,
removes all passkeys, revokes client access and ends browser sessions. Sign in
again and create and verify a passkey. If you also lost primary sign-in, ask the
operator about offline recovery.

![Security audit with readable events, actors, targets and outcomes](images/getting-started/23-audit.png)

Administrators use **Security & audit** to filter bounded UTC records and see
current directory labels for recorded targets. **Recorded references** shows
stored identifiers. **Registered applications** manages native app registration.
Removing a registration revokes all grants for that app.

![System diagnostics with configured origin, schema and HTTP observations](images/getting-started/24-diagnostics.png)

Use **System & diagnostics** to check the configured URL and issuer, versions,
database query availability for the current request, HTTP observations since
startup and the bounded list of recent server errors. Use the request reference
when checking operator logs. Backup and HTTPS renewal are tracked separately.

## 14 — Import and export

![Import form showing a journal destination and the iCalendar file upload](images/getting-started/20-import-destination.png)

Open a writable resource and select **Import an iCalendar file**. For an initial
migration, use **Import existing data** from Overview, then create or choose a
destination. Calendars take VEVENT, task lists VTODO and journals VJOURNAL. Check
the destination before choosing the file. Limits are 64 MiB per file, 10,000 UID
series and 1 MiB per series. Recurrence exceptions stay with their UID. If asked
to confirm identity, do that before choosing the file.

![Import preview identifying the destination and the ready and conflict totals](images/getting-started/21-import-preview.png)

Review ready, skipped, conflicting and rejected entries. Identical items are
skipped. A different item with an existing UID is a conflict. Existing items
are preserved. The preview expires after ten minutes, and a newer successfully
staged import replaces it. Discard a preview to return without importing.
Choose **Confirm import** only after reviewing the ready items.

![Committed import report with one imported journal entry](images/getting-started/22-import-result.png)

The report shows committed results. If the browser loses the response, open the
retained report again. If it has expired or the server restarted, check the
destination in a connected app before trying again. A still-valid preview can
be retried when BusyStack can establish a rollback.

For export, open a resource and select **Export events**, **Export tasks** or
**Export journal entries** when content is available. An empty resource explains
how to add content. Matching calendar contexts produce one VCALENDAR. Distinct
contexts require a stream of complete VCALENDAR objects (`-stream.ics`).
BusyStack imports both. Thunderbird 159.0's native file importer requires a
single VCALENDAR. See [Portability](portability.md).

## 15 — Optional Keycloak

Keycloak is useful when an organization already operates an identity provider.
Standalone BusyStack works without it. Establish the first administrator through
chapter 6 before enabling federation. Keep an independently recoverable local
administrator for provider outages.

The operator registers an OIDC confidential client with authorization-code flow,
S256 PKCE and the exact callback `<PUBLIC_URL>/login/oauth2/code/upstream`. Disable
unused provider flows. Configure issuer, client ID, protected client secret and a
closed subject allowlist using [the complete Keycloak configuration](keycloak.md).
Compose has no mandatory Keycloak service. Mount protected operator configuration
and file-backed secrets through your deployment configuration; the detailed guide
provides the actual property names. Do not put provider secrets in a public file.

Allowlisted subjects are verified immutable issuer/subject identities, not emails
or matching display names. They provision ordinary local authorization identities.
Upstream roles do not grant BusyStack administrator privileges. Existing users may
explicitly link an organization identity only after recent MFA. Linking removes
local-password fallback, preserves local collection identity/passkeys, rotates the
security generation and revokes existing independent access.

Users choose organizational sign-in, then the same local passkey enrollment or
verification appropriate to actual account state. They do not configure Keycloak.
Organization sign-in requests fresh provider authentication (`prompt=login`,
`max_age=0`) and requires a recent verified authentication time. The ten-minute
primary-sign-in and passkey policy still applies to sensitive portal operations;
complete a fresh provider sign-in when primary authentication expires.
Provider tokens are not DAV passwords. BusyStack discards upstream access/refresh
tokens after login and uses its own application-access mechanisms.

Local login remains available during a provider outage. Managed users cannot fall
back to a local password. Already issued managed-user access may continue within
its existing freshness bound; an outage does not extend it. Default upstream
freshness is 24 hours (operator-configurable down to one minute). Upstream disabling
is not immediate push revocation of all BusyStack access; local disabling and local
revocation remain available. Recovery preserves the exact issuer/subject mapping.

## 16 — Backups and recovery

From the deployment directory, create a new private backup destination:

```sh
install -d -m 700 /private/backups
bash scripts/backup.sh /private/backups/busystack-before-update.dump
(cd /private/backups && sha256sum --check busystack-before-update.dump.sha256)
```

Replace `/private/backups` with an operator-owned backup directory. Expect
`Created database archive and checksum...` and a checksum `OK`. The script refuses
to overwrite an existing archive or checksum. Keep both files, configuration,
secret files and exact release/image identity securely, separately and preferably
off-host. Backups contain sensitive account/content/security state. A checksum
proves file integrity, not successful restoration; rehearse recovery.

Restore only into an **isolated empty deployment**, with a different project name
and port, its own correctly configured secrets, and every application instance
stopped. Do not restore over a production database. In that new extracted/configured
release directory:

```sh
docker compose config --quiet
docker compose pull --policy missing
docker compose up -d --no-build --wait database
bash scripts/restore.sh /private/backups/busystack-before-update.dump
```

The script checks the checksum, refuses a running application/nonempty target,
and accepts complete successful histories from V1 through V18. It preserves data,
ownership, shares and identity mappings, disables every account, changes recovery
and account epochs, removes restored sessions/passkeys/recovery codes/DAV credentials
and OAuth grants, clears one-time issuance intents, revokes refresh families, and invalidates outstanding initialization
codes. An initialized database remains initialized. Expect the explicit restoration
and invalidation confirmation. On any failure keep the application stopped.

For an older supported schema, start the current image once with every account
disabled to apply forward migrations, then stop it before recovery:

```sh
docker compose up -d --no-build --wait --wait-timeout 240 application
docker compose stop application
```

After independently verifying each local user's identity, prepare an owner-only
file containing a **new** password. Use a password manager or protected local
editor; never put its value in command arguments. Mount it using the host owner's
UID so the owner-only permission check is meaningful:

```sh
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" \
  --volume /private/path/new-password:/recovery-password:ro \
  application --spring.main.web-application-type=none \
  --busystack.recover-account=true \
  --busystack.recovery-username=administrator \
  --busystack.recovery-password-file=/recovery-password
```

Use the exact existing username and actual private absolute file path (mode 0600,
private parent). Recover accounts individually; never bulk-enable restored accounts.
Delete the temporary password file after securely recording the replacement.
Start the application, sign in and enroll fresh passkeys, then reissue application
access. Review restored administrator roles, sharing and OAuth registrations, which
may reflect old permissions. Verify actual event, task and journal contents, rejected old
credentials, shared read/write boundaries, and fresh synchronization.

Externally managed accounts need provider-side recovery and the separate offline
command with their **existing exact** issuer/subject, without a replacement password:

```sh
docker compose run --rm --no-deps application \
  --spring.main.web-application-type=none \
  --busystack.recover-external-account=true \
  --busystack.recovery-username=organization-user \
  --busystack.recovery-issuer=https://identity.example.org/realms/organization \
  --busystack.recovery-subject=verified-existing-subject
```

Keep applications stopped during this operation. It does not change identity mapping
or create a local-password fallback. Follow [operations](operations.md#backup-and-isolated-restoration)
for recovery safeguards, ownership and verification scope. The established offline
bootstrap remains available only for an untouched empty database; ordinary new
installations use protected browser initialization.

## 17 — Updates

Read target release/migration notes. Back up and verify a restoration rehearsal
before updating. Obtain and verify the newer deployment archive as in chapter 3.
Keep the same intended Compose project/database volume, public URL and secret paths;
changing the extraction directory must not accidentally select a new project.
Copy your reviewed configuration into the new extracted package. Keep secret files
outside either package. Preserve optional operator overrides deliberately, reviewing
them against the new Compose file rather than replacing the new file wholesale.

From the old deployment directory stop the application. From the configured new
one validate, pull and start:

```sh
docker compose config --quiet
docker compose pull --policy missing
docker compose up -d --no-build --wait --wait-timeout 240
docker compose ps
```

Flyway validates published migration checksums and applies forward migrations.
V17 added journals. V18 adds transactionally consumed browser issuance intents
and a recovery-code generation marker. Existing accounts close initialization on
upgrade. Verify HTTPS, returning-user sign-in, resource contents, sharing and
synchronization.
Keep the previous artifact and backup for a controlled recovery plan; changing an
image does not undo database migrations. Automatic downgrade is not promised.

## 18 — Stopping and uninstalling

`docker compose stop` stops services while retaining containers and storage.
`docker compose up -d --no-build --wait` starts them again.
`docker compose down` removes this project's containers/network and retains the
database volume. Keep `.env`, secrets and proxy configuration to resume later.

**Destructive:** `docker compose down --volumes` also deletes this project's
persistent database. Run it only after independently verifying backups and
explicitly deciding to destroy that installation. Confirm the selected project
first with `docker compose ps`. It is not an update or ordinary restart command.

After deliberately uninstalling, remove the corresponding proxy configuration and
private configuration/secrets when no longer needed, following your retention
policy. Backups and copied secret material need separate secure disposal.

## 19 — Troubleshooting

Use the symptom to choose a safe check. Keep credentials, backups and detailed
infrastructure diagnostics private. Do not disable TLS, CSRF or passkey verification
to make an error disappear.

| Symptom | Supported causes and safe verification | Corrective action |
| --- | --- | --- |
| `docker version` cannot contact the daemon | Docker is stopped or operator access is unavailable. Check local service status and approved Docker permissions. | Start Docker using host administration; obtain authorized daemon access. Do not make its socket public. |
| Compose configuration fails | Required `.env` values/absolute secret files are missing, wrong working directory, or Compose is too old. | Run `docker compose config --quiet` in the extracted directory, check paths and Compose ≥2.24.4. |
| PostgreSQL stays unhealthy | Secret mount access, failed first initialization, storage or existing-role password mismatch. Check `docker compose ps` and private database startup logs. | Correct permissions/storage or coordinate role/secret rotation. Preserve the volume; changing a file alone does not rotate a role. |
| Application readiness fails | Database unavailable, invalid public URL/issuer, migration failure or resource limits. Check application startup logs and health status. | Correct the actual configuration/database problem and restart; do not edit applied migrations or bypass Flyway validation. |
| Browser/curl certificate error | Wrong hostname, expired/incomplete chain, or CA not trusted. Check the configured URL and certificate through normal client diagnostics. | Install/renew the correct chain and establish intended CA trust. Never disable validation. |
| Redirects or discovery use the wrong address | Wrong configured public URL/issuer or mismatched proxy prefix. Compare `.env`, proxy examples and Connections. | Set equal canonical URLs; preserve prefix; recreate application with `docker compose up -d --no-build --wait`. |
| `/calendar` links fail or discovery is missing | Prefix was stripped or host-root discovery mapping omitted. Check selected proxy example and `curl --head` discovery. | Restore the preserved prefix and explicit well-known mappings; validate proxy before reload. |
| Initialization code is unavailable | Expired, replaced, used, absent or invalidated after repeated failures. It is intentionally not distinguished publicly. | Issue a replacement locally with `bash scripts/initialize.sh`, then re-enter it within 15 minutes. |
| Local initialization refuses after setup | Database already has initialization/account history. Check normal sign-in and your deployment project. | Use ordinary sign-in or controlled recovery. Never delete accounts/setup state to reopen registration. |
| Login fails | Incorrect local credentials, disabled account, wrong primary method or throttle cooldown. | Check username/manager entry, use organization sign-in for managed accounts, allow cooldown, or ask the administrator/operator for controlled recovery. |
| Passkey prompt does not appear | Insecure/wrong URL, unavailable browser capability/provider or expired primary sign-in. | Use configured HTTPS in a supported browser, unlock the intended provider, return to the sign-in page when needed. |
| Prompt closes or reports interaction incomplete | Cancellation, timeout or no available credential can produce the same browser error. | Retry deliberately, select this account's passkey, or use recovery. Do not assume every error is cancellation. |
| Challenge expired/already used | Ceremony exceeded five minutes or was submitted again/from stale state. | Request a fresh prompt. Never replay an old assertion. |
| Network failed after enrollment | Server may have committed while its response was lost. | Return to the enrollment page so BusyStack can check whether it was saved. Verify the saved passkey if it appears. |
| MFA expires while a page remains open | Ten-minute factors expired independently of session idle timeout. | Follow the contextual sign-in/verification action, return to the intended page, and review/resubmit forms yourself. |
| Session invalidated | Password change, recovery, disabling or epoch change ended access. | Sign in again; contact administrator if disabled. A stale page cannot authorize a protected mutation. |
| Application password rejected | Account password used instead, wrong username, expired/revoked password, disabled account or managed-user upstream freshness expired. | Check metadata in Security; issue a replacement after MFA, update client, revoke old access; managed users may need organization sign-in. |
| Client discovers no calendars | No collection/shared grant, wrong URL/prefix, discovery route or client-specific username retention. | Check Calendars, tasks & journals and configured address; use GNOME's username-bearing address; ask owner to review effective access and refresh discovery. |
| Keycloak unavailable | Provider discovery/login cannot complete. Local startup remains independent. | Restore the provider/certificate/configuration. Use a local administrator for operator work; managed users have no password fallback. Existing freshness is not extended. |
| Import rejected or busy | Wrong collection type, invalid file, UID conflict, 64 MiB/10,000-series/1 MiB-resource bounds, processing budget or temporary capacity. | Read preview/error, preserve original, correct/split the migration, discard an unused preview or retry later. Never assume a conflict overwrote data. |
| Import preview expired after restart | Preview tokens are temporary and do not survive restart. | Check destination if an outcome was uncertain, upload again, review and confirm the new preview. |
| Backup refuses to overwrite | Archive or `.sha256` already exists. | Choose a new private filename. Preserve the earlier backup. |
| Restore refuses running/nonempty target | Safety checks protect existing data. | Stop all application instances and use a separately configured empty database/project. Never remove production data to satisfy the check. |
| Restore reports unsupported/failed/gapped history | Archive history is outside reviewed schema V1–V18 or invalid. | Keep application stopped; use the matching reviewed recovery procedure and original archive. Do not suppress the check. |
| Restored accounts cannot sign in | Restore deliberately disables accounts and revokes credentials. | Independently verify each user and run the documented local/external recovery command, then re-enroll factors. |

For deeper reference: [operations](operations.md), [portal](portal.md),
[administration](administration.md), [passkeys](passkeys-and-mfa.md),
[account security](account-security.md), [Keycloak](keycloak.md),
[OAuth](oauth.md), [groups](groups.md), and [portability](portability.md).

## 20 — Advanced JAR operation

For direct JAR operation, supply an isolated PostgreSQL database, trusted HTTPS
proxy and the private configuration described in [Operations](operations.md).
Use the verified release JAR and Java 25 specified for the release. From the
directory containing the JAR, after configuring the environment, run:

```sh
umask 0077
java -XX:+ExitOnOutOfMemoryError -jar caldav-server-@VERSION@.jar
```

For the primary Compose installation, continue using the bundled scripts and
health checks. The JAR path requires the operator to manage startup, private
temporary storage, database access and recovery separately.
