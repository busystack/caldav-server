# Install BusyStack CalDAV Server @VERSION@

Supported container platform: Linux AMD64 with Docker Engine and Compose v2
(2.24.4 or newer). Supply a domain, DNS, a trusted HTTPS certificate/private key
and a reverse proxy on the same host. The download supplies no domain or
production certificate. PostgreSQL 18.6 is included as a private persistent
service. No host Java or Maven is required for this installation.

Download `caldav-server-@VERSION@-deploy.tar.gz`, `SHA256SUMS` and
`release-manifest.json` from https://github.com/busystack/caldav-server/releases/tag/v@VERSION@.
Verify the downloaded archive against SHA256SUMS before extraction. The release
manifest is authoritative for the source, JAR checksum, tested Linux AMD64 image
registry digest and qualification evidence. Checksums detect corruption; the
immutable GitHub release and its release attestation bind the publisher's assets.

```sh
sha256sum --check --ignore-missing SHA256SUMS
tar -xzf caldav-server-@VERSION@-deploy.tar.gz
cd caldav-server-@VERSION@-deploy
cp .env.example .env
```

Edit `.env` with the actual HTTPS URL/issuer and private absolute secret paths.
Create distinct database secrets as shown in `docs/operations.md` (private parent
directory mode 0700, database files readable by container UIDs). Do not put secrets in `.env`, command lines or the archive. Then, from this extracted directory:

```sh
docker compose config --quiet
docker compose pull
docker compose up -d --no-build --wait
docker compose ps
```

Compose pulls the published version **and registry digest** and mounts only the
included initialization script and your explicit secret files. Configure your
proxy using `nginx-root.conf.example` or `nginx-subpath.conf.example`, replacing
the hostname and certificate paths. Validate the proxy configuration before
reload. Do not expose the database or application HTTP port to the network.
For a subpath, set both URLs to the same `/calendar` suffix and preserve that path.

Follow the complete illustrated [Getting Started guide](docs/GETTING_STARTED.md).
After HTTPS is configured, a fresh server shows **Set up BusyStack**. Issue its
15-minute, single-use code locally from this deployment directory:

```sh
bash scripts/initialize.sh
```

Choose **Create administrator account**, enter the code and choose your username
and password in the browser. Setup does not sign you in. Sign in normally, create
your first passkey, then verify it. Follow the guide for recovery codes, personal
calendars or account administration, and application passwords/client setup.
The account password is not the DAV application password.

## Updates, backup, restoration and removal

Back up privately using `bash scripts/backup.sh /private/backup/new.dump`; retain
its `.sha256`, the deployment configuration, image digest and secrets separately.
For restoration, use a new empty database/project with the application stopped,
then `bash scripts/restore.sh /private/backup/new.dump`. The restore procedure
invalidates restored accounts/security credentials before controlled individual
recovery; follow the complete operations guide before starting normal service.
Never restore over a live database. Check the archive checksum first.

For an update, read the target release/migration notes, back up, stop the
application, replace the bundle with the target release's separately verified
bundle, preserve the intended project/database volume and secret paths, pull and
start. Changing an image does not reverse PostgreSQL migrations. Use the target
release's version and digest rather than `latest`.

`docker compose down` removes services while retaining database storage.
Only after verifying backups and explicitly deciding to discard this installation,
`docker compose down --volumes` deletes its database. Remove its proxy configuration
and private secrets when no longer needed. Keep backups under your retention policy.

## JAR alternative

Download `caldav-server-@VERSION@.jar` and verify its SHA-256. Use official Java 25
(the selected qualified runtime is Temurin 25.0.4.1+1) and PostgreSQL 18.6. Configure
an application-owned PostgreSQL database/role, file-backed Spring config-tree
secrets, equal canonical HTTPS public URL and issuer, and loopback listener.
Use the operations guide's bootstrap/recovery commands with the downloaded JAR
path in place of `target/caldav-server-@VERSION@.jar`. Run `java -jar
/path/caldav-server-@VERSION@.jar`. A JAR needs a host JDK/runtime and an operator-managed
process; it does not install a service manager or database. Container deployment
above needs neither host Java nor a compiler.

License: Apache-2.0; retain the included LICENSE, NOTICE, THIRD-PARTY-NOTICES and
third-party license material. Client support is version/configuration scoped;
read `docs/releases/@VERSION@.md` for tested profiles and exclusions. The account UI
is not a calendar editor; no invitations, scheduling, reminders or CardDAV.
