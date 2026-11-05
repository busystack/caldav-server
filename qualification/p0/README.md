# P0 journey qualification

Use the existing branch and a local development commit containing the runtime and
these tests. No public release or push is performed. The official release builder
continues to require a clean tree. The local image helper permits unrelated
qualification edits only after proving every runtime/build input equals HEAD;
its receipt explicitly records the uncommitted qualification paths.

Run the repository's complete Maven verification first with its documented Java 25,
Docker and independent-client prerequisites. Preserve the Maven log and XML results.
Then, sequentially (browser fixtures require stable host networking):

```sh
python3 qualification/p0/build.py --image busystack-caldav:p0-local
python3 qualification/p0/run.py --application-image busystack-caldav:p0-local
```

The latter uses an empty isolated Compose database, actual TLS with normal
certificate validation, the exact operator code command, and Chromium's CTAP2
virtual authenticator. It never pre-creates an administrator. It captures twenty
screens at 1280×900 with light production CSS, checks reflow at 390px, masks the
one-time application password's containing block, and destroys only its owned
project/volume. No native browser/password-manager dialog is fabricated. Fixture
secrets exist only in a private temporary directory; no secrets enter screenshots
or the success receipt. Do not print HTTP client exception headers or dump cookies.

A passing run writes `target/qualification/p0/browser-result.json` and PNGs. Copy
only the twenty named PNGs to `docs/images/getting-started/`, inspect them, and
commit them. The final capture must use the final qualified runtime. Qualification
results must distinguish Chromium virtual-authenticator evidence from manual
Firefox/Bitwarden acceptance.

`package.py` assembles an **unpublished local** deployment archive through the same
file-selection and deterministic archive functions as `scripts/release-bundle.py`.
It validates all guide links and twenty PNGs, verifies source/runtime equivalence
and the exact container JAR checksum, and records actual image/source identities.
Its metadata does not invent a CI run or a published release:

```sh
python3 qualification/p0/package.py --image busystack-caldav:p0-local --output target/p0-local-package
python3 qualification/p0/run.py --application-image busystack-caldav:p0-local \
  --deployment-directory target/p0-local-package/caldav-server-0.2.0-deploy \
  --proxy --output target/qualification/p0-package
```

The proxy profile uses the guide's complete `up -d --no-build --wait
--wait-timeout 240` command and both production health checks. Run it again with
`--context-path /team` to check the packaged subpath example. The default
administrator activity is **Manage users**; `--administrator-activity personal`
also exercises **Set up my calendar** before continuing the same complete journey.

For final manual acceptance, prepare a separate fresh project using the extracted
package. The following starts an isolated Nginx HTTPS deployment using the package's
actual root proxy example. It creates no accounts and does not issue a code:

```sh
python3 qualification/p0/manual.py \
  --deployment-directory target/p0-local-package/caldav-server-0.2.0-deploy \
  --output .qualification-runs/p0-manual
```

The resulting private `acceptance.json` identifies the exact HTTPS address,
installation directory and disposable CA. Use a new Firefox profile; trust the CA
in that profile through Firefox's certificate settings and install/configure
Bitwarden yourself. Do not change an existing profile or vault automatically.
From the displayed installation directory, issue `bash scripts/initialize.sh` and
follow Getting Started chapters 6–7. Choose a fresh test password yourself; the
agent has not prepared a login or registered a passkey in a password manager.

Manually record: setup → administrator creation → password sign-in → Bitwarden
registration → separate verification → protected collection creation → application
password issuance → sign out → returning sign-in → existing-passkey verification.
Record exact Firefox/Bitwarden versions, result and any cancellation/provider
behavior. Bitwarden qualification is not a hardware user-presence claim. Until the
user performs this procedure, that acceptance is **pending**.

Cleanup only the isolated project: from its displayed installation directory run
`docker compose down --volumes --remove-orphans`. Its disposable CA expires after
two days; regenerate a new fixture for later acceptance. Public anonymous download
and registry-pull acceptance requires actual published assets and is distinct
from these explicitly local pre-publication tests.
