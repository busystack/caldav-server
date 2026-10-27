# Third-party license inventory and review

The exact runtime module inventory is the generated CycloneDX file, `target/classes/META-INF/sbom/application.cdx.json`, embedded at `META-INF/sbom/application.cdx.json` in the executable JAR. It records each Maven package URL, resolved version and POM-declared license. CI retains this file for the artifact it built. Use [the build instructions](operations.md) to regenerate it; the dependency-management baseline and [version pins](versions.md) are maintained separately.

The 2026-10-27 final source build contains 112 runtime Maven component entries, all with license metadata. The project root component records NOASSERTION because the owner has not chosen its license. Counts are observations of that generated artifact, not a substitute for regenerating the inventory after dependency changes. POM metadata is evidence to inspect, not a substitute for the license files and notices in the actual distribution.

| Specific module/artifact | Observed license evidence and review decision |
| --- | --- |
| `org.springframework.session:spring-session-core:4.1.1` and `spring-session-jdbc:4.1.1` | Published POMs name “Broadcom Foundation License” without a URL; both published JARs include Apache-2.0 in `META-INF/LICENSE.txt`. The [tagged source license](https://github.com/spring-projects/spring-session/blob/4.1.1/LICENSE.txt) is also Apache-2.0. Retain this discrepancy for distribution review; the SBOM is not silently relabeled. |
| `org.mnode.ical4j:ical4j:4.4.0` | POM calls the license “iCal4j - License”; the selected source/license is BSD-3-Clause. Its parsing/recurrence use does not make it a CalDAV engine. Application-vendored BSD source corrections retain upstream headers and recorded provenance: [parser patch](ical4j-parser-patch.md) and [timezone patch](ical4j-timezone-patch.md). |
| `ch.qos.logback:logback-core:1.5.38`, `logback-classic:1.5.38` | Metadata includes both EPL-2.0 and LGPL-2.1-only alternatives. Inspect the supplied dual-license terms and notices rather than assuming the logger is Apache licensed. |
| `jakarta.annotation:jakarta.annotation-api:3.0.0` | Metadata includes EPL-2.0 and GPL-2.0-with-classpath-exception alternatives; retain the supplied notices. |
| Bedework protocol probe | Only `investigation/protocol` depends on Bedework. Its exact module/transitive inventory and license findings belong to that probe and [ADR 0001](adr/0001-protocol-engine.md); it is absent from the production runtime SBOM. |
| Browser, proxy and Keycloak fixtures | Test-only pinned images carry their own package licenses; neither those images nor their operating-system dependencies are implied to be covered by the Java runtime inventory. See [versions](versions.md). |

Hashes for the Spring Session discrepancy, captured from the resolved Maven artifacts:

| Artifact | SHA-256 |
| --- | --- |
| `spring-session-core-4.1.1.jar` | `01b836bfcc5b64f7039c3430980878112a10db5066eda4274a1d3eb874e49733` |
| `spring-session-jdbc-4.1.1.jar` | `669e4388684949edb0c5aef0a7f4e15af86d6c87ffb90df3145f073746870529` |
| Either JAR's `META-INF/LICENSE.txt` | `aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe` |
| `spring-session-core-4.1.1.pom` | `0dde28a7f8536093e8191cf2c7432318db69f53c3fba8e903a23cf1663e271d0` |
| `spring-session-jdbc-4.1.1.pom` | `bf661ce61020ae171e5495b0b9286ef725a04ebc8ee4e912a0453305bf585526` |

Public-distribution review must cover the selected project license, applicable dependency license alternatives, modified upstream schema attribution, required license/notice delivery, container OS packages and corresponding source obligations where applicable. The owner has not selected a project license. This file records engineering evidence and unresolved review work; it does not supply that authorization or a legal opinion.
