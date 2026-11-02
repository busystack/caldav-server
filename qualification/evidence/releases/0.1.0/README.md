# v0.1.0 preparation evidence

`verification.json` records four passed native editor profiles against the exact
v0.1.0 executable from successful CI run 41357992646, attempt 1, source
`5d09ecb2e241a97f429f71aecfbfd9fcd166b861`. The JAR SHA-256 is
`bff028ac0072134458710e22e63ce433f96d4fd2619b217049d619c998151792`.
The receipt binds the executable to the qualified Dockerfile and Compose inputs.
It identifies the actual CI image ID, loaded Docker image ID and canonical image
configuration digest separately. No GHCR v0.1.0 digest or publication is claimed.

- `clients/`: Thunderbird 159.0 system Vancouver, explicit Vancouver and explicit
  Berlin; GNOME Calendar 50.0 / EDS 3.56.2 Vancouver. Thunderbird includes native
  screenshots, HTTP observations and raw calendar data. GNOME includes actual
  editor/control snapshots, finite-three recurrence, client/server restart byte
  and ETag comparisons, and All Events deletion (DELETE 204, GET 404). Compressed
  JSON snapshots are exact retained accessibility observations, scoped to the
  disposable client PID. Read them with Python's `gzip` module or `gzip -dc`.
- `application-verification.json`: 386 unit, 218 integration, 6 protocol and 4
  OAuth probe tests; zero failures, errors or skips. `ci-artifact-results/` retains
  successful packaged operation/browser/recovery and security summaries from the
  exact successful CI artifact, including the independent packaged clients.
- `release-regressions.log`: 29 tests covering versions 0.1.0, 0.2.0 and 1.0.0,
  qualified-source/controller separation, exact retained payloads, failed gates,
  cross-engine image loading and exact registry-manifest promotion. Distribution,
  security and recovery helper suites also passed (11, 11 and 12 tests).
- `developer-build-verification.json` and `docker-contract-rejections.json`:
  automatically versioned ordinary developer image identity/JAR verification;
  actual rejection of absent or inconsistent Docker metadata and unwrapped
  Compose configuration. The original ordinary build transcript was in `/tmp`
  and lost on an intervening host restart; fresh image/JAR checks are retained.
- `jar-version-only-comparison.json`: 560 unchanged JAR entries; only the four
  application version metadata/SBOM entries differ from immutable v0.0.1.
- `attempts/` and the GNOME workflow record retain failed automation/setup
  attempts. Two host restarts interrupted temporary controllers. The same private
  fixture/profile was resumed after checking its endpoint and TLS certificate;
  no installed client, system trust, application code or security timeout changed.

The interactions used real editor controls with isolated profiles and normal TLS
verification. They are automated client qualification, not a claim of manual human
or physical authenticator testing. Private profiles, credentials and unrelated
desktop content are excluded. Evidence was scanned for the disposable DAV secret
and its Basic authorization encoding before retention.

The auxiliary browser fixture paused at its backup checkpoint while native GUI
checks ran and was interrupted by a host restart. It is not claimed as another
complete browser-restoration pass; the linked CI runs passed those complete suites.

`evidence-sha256.json` inventories these retained files. Historical v0.0.1 evidence
has not been changed. Official release staging, complete deployment-archive
installation and anonymous public v0.1.0 installation remain pending the
owner-approved squash integration and qualification of that final main commit.
