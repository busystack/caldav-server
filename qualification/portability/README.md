# Portability correction qualification

`PortalManagementIT` exercises actual PostgreSQL and HTTPS multipart upload,
preview, paginated results, confirmation, rollback/retry, concurrent confirmation,
security epochs, permissions, and a 10,000-resource / more-than-60-MiB round trip.
Its maximum case also overlaps real exports with a retained preview and checks
capacity rejection, byte-identical downloads, disk/reservation/heap/RSS samples.
Measurements are in `target/qualification/portability/large-http.json`. Unit tests
exercise private permissions, recognized-orphan cleanup, symlink rejection,
cleanup failures, reservations, file count, stage expiry and uncertain completion.
These are separate from packaged browser and deployment results.

After the complete Maven verification and local image build, run:

```sh
python3 qualification/portability/temporary_storage.py \
  --application-image busystack-caldav:YOUR_VERIFIED_LOCAL_TAG \
  --java-home /absolute/path/to/selected-jdk \
  --jar target/caldav-server-0.2.0.jar
```

This fixture creates only its own disposable Compose project and host-private
directory. It verifies the read-only application filesystem, 384 MiB tmpfs,
private permissions, forced container termination and restart, then runs the exact
JAR against that disposable PostgreSQL instance with a persistent private staging
directory. Seeded recognized files model interrupted staging. It proves they
remain while the standalone process is stopped, disappear on its next startup,
and unrelated files survive. It does not substitute for actual HTTP import or
in-flight database-transaction interruption tests. Its receipt includes image/JAR
identity and is written to `target/qualification/portability/temporary-storage.json`.

Run the documented packaged browser profiles in
[passkeys](../passkeys/README.md) against the same image. Their form checks cover
summary focus, links to invalid controls, accessible error associations,
nonsensitive value preservation, password omission, correction/resubmission and
desktop/mobile screenshots. Recovery uses the checkout's existing qualification
checkpoint changes; do not omit them from the browser image. Run browser profiles
sequentially, after other Docker integration jobs finish, to avoid unrelated
network changes during navigation. Existing native client fixtures qualify DAV
separately from Thunderbird's single-VCALENDAR file-parser limitation.

Generated receipts qualify only their actual source and artifact. Fixture presence
is not a passing result, and virtual WebAuthn does not qualify physical devices.

For actual interruption during confirmation, add `--portability-crash` to a full
packaged browser profile (for example root `--groups --portability-crash`). The
owned fixture pauses the second insert after the first uncommitted resource write,
observes PostgreSQL's checkpoint, sends SIGKILL to only that application's
container, and checks zero resources after rollback. The same real browser then
verifies the old preview is invalid and a fresh import commits exactly two items.
The fixture removes its trigger/function and writes an image-bound interruption
receipt. No production endpoint, authentication bypass or durable preview recovery
is added. This differs from merely seeding orphan files for startup cleanup.
