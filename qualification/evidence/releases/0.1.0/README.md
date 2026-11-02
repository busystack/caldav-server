# v0.1.0 qualification

[verification.json](verification.json) binds the passed native-editor profiles to
the actual Maven-versioned executable checksum and the Dockerfile/Compose input
hashes. It records Thunderbird 159.0 with system Vancouver, explicit Vancouver
and explicit Berlin, and GNOME Calendar 50.0 / EDS 3.56.2 with Vancouver.

The profiles exercised creation, synchronization, time editing and reopening,
bounded recurrence, restart persistence and deletion; Thunderbird also exercised
task completion and edited occurrences. These are automated real-client checks,
not manual-human or physical-authenticator certification.

The receipt links the original results and successful implementation CI runs.
Subsequent documentation-only source revisions retain this qualification only
when the JAR checksum and runtime-input hashes match. Official release source,
CI attempt, image digest and asset checksums are recorded in the release manifest.
Detailed diagnostics are archived through the [evidence index](../../README.md).
