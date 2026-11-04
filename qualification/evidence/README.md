# Qualification records

Maintained tests and protocol fixtures live in `src/test`, `qualification/` and
`investigation/`. Generated logs, screenshots and intermediate execution records
belong in CI artifacts or the external project audit archive.

- [v0.1.0 qualification](releases/0.1.0/README.md) records the qualified executable,
  runtime inputs, real-client scope, results and successful CI references.
- [v0.0.1 summary](releases/0.0.1/summary.json) preserves historical identity and
  scope; it is not evidence for a different executable.
- Each published release's `release-manifest.json` identifies its exact source,
  successful verification run/attempt, JAR checksum, registry manifest digest and
  deployment archive checksum. Local Docker IDs are not registry digests.

## Corrective engineering receipts

Local v0.2.0 corrective receipts live under `.qualification-runs/`, with a final
`corrective-final-report.json` identifying the exact corrective commit, preserved
qualification-file hashes, runtime artifact hashes, commands, counts and remaining
gates. Generated evidence is not a release manifest. Consult
[portability qualification](../portability/README.md) for the additional storage
and HTTP regressions. Historical passing records do not qualify changed source.

## Historical records

The detailed pre-cleanup records remain unchanged in
[Git history](https://github.com/busystack/caldav-server/tree/f3ba60d360117c44b3dfeb6dc9848778a98ef06a/qualification/evidence)
and the external audit archive `v0.1.0-public-cleanup-20261102/source-before-cleanup.tar.gz`.
Its source commit is `f3ba60d360117c44b3dfeb6dc9848778a98ef06a` and archive SHA-256 is
`d8c1ee2ddd06cdc01f882366518a023b0ec02224b329c0f937cd8549e7365783`.
The archive retains unsuccessful and interrupted attempts alongside later
successful results; removal from the current source tree changes none of them.
No credentials or private client profiles are needed to build or install a release.
