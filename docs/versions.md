# Dependency and distribution baseline

Verified on 2026-10-27. The application is an implementation checkpoint, not a qualified production CalDAV release. The protocol and native OAuth risk records identify unfinished acceptance requirements.

| Component | Selected version | Source and license |
| --- | --- | --- |
| Eclipse Temurin OpenJDK | 25.0.4.1+1, Java 25 LTS | [Adoptium releases](https://adoptium.net/temurin/releases/?version=25), [OpenJDK license](https://openjdk.org/legal/gplv2+ce.html): GPL-2.0 with Classpath Exception |
| Maven | 3.9.12 | [Maven release history](https://maven.apache.org/docs/history.html): Apache-2.0 |
| Maven Wrapper | 3.3.4 | [Wrapper](https://maven.apache.org/wrapper/): Apache-2.0; distribution and SHA-256 pinned in `.mvn/wrapper/maven-wrapper.properties` |
| Spring Boot | 4.1.2 | [Published parent](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/4.1.2/), [requirements](https://docs.spring.io/spring-boot/system-requirements.html): Apache-2.0 |
| Spring Security | 7.1.2 | Managed by [Boot BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom): Apache-2.0 |
| Spring Session JDBC/core | 4.1.1 | Boot BOM; published JAR/tagged source Apache-2.0, POM metadata differs; [specific artifact evidence](third-party-licenses.md) |
| Spring Framework | 7.0.10 | Boot BOM: Apache-2.0 |
| Embedded Tomcat | 11.0.27; Jakarta Servlet 6.1 | Boot BOM: Apache-2.0 |
| PostgreSQL server | 18.6 | [Supported releases](https://www.postgresql.org/support/versioning/), [license](https://www.postgresql.org/about/licence/): PostgreSQL License |
| PostgreSQL JDBC | 42.7.14 | Boot BOM; [license](https://jdbc.postgresql.org/about/license/): BSD-2-Clause |
| Flyway | 12.4.0 | Boot BOM; [Community source](https://github.com/flyway/flyway): Apache-2.0 for the required core and PostgreSQL modules |
| iCal4j | 4.4.0 | [Published artifact](https://repo.maven.apache.org/maven2/org/mnode/ical4j/ical4j/4.4.0/), [source](https://github.com/ical4j/ical4j): BSD-3-Clause |
| Testcontainers | 2.0.5 | Boot BOM; [source](https://github.com/testcontainers/testcontainers-java): MIT; test scope only |
| JUnit Jupiter | 6.0.3 | Boot BOM; EPL-2.0; test scope only |
| Playwright / bundled Chromium | 1.61.1 / 149.0.7827.55 | [Playwright](https://playwright.dev/): Apache-2.0; Chromium contains separately licensed components; test-only browser fixture |
| Nginx | 1.30.6 stable | [Official download](https://nginx.org/en/download.html), [license](https://nginx.org/LICENSE): BSD-2-Clause; optional test proxy, not an additional default service |
| Keycloak | 26.8.1 | [Official download](https://www.keycloak.org/downloads): Apache-2.0; optional test fixture only |
| OWASP Dependency-Check Maven | 13.0.0 | [Published artifact](https://repo.maven.apache.org/maven2/org/owasp/dependency-check-maven/13.0.0/), [configuration](https://dependency-check.github.io/DependencyCheck/dependency-check-maven/configuration.html): Apache-2.0; CI tool only |

The Boot dependency-management baseline selects framework versions together. No Spring Framework or Spring Security version is independently overridden. Boot 4.1.2 supports Java 17 through 27 and Servlet 6.1; Java 25 is the application toolchain requirement. Java's LTS classification follows the [Java support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html).

Build plugins and formatting tools are explicitly versioned in [pom.xml](../pom.xml). `./mvnw package` generates `target/classes/META-INF/sbom/application.cdx.json`, a CycloneDX inventory of runtime dependencies and their declared licenses, which is included in the application artifact. This is evidence for review, not a legal opinion or a completed dependency/license audit. The embedded SBOM identifies each exact runtime Maven module with its group/name/version, package URL and declared license. Inspect it directly with `unzip -p target/caldav-server-0.0.1-SNAPSHOT.jar BOOT-INF/classes/META-INF/sbom/application.cdx.json`; this avoids a second stale dependency inventory. Bedework and its probes stay under `investigation/protocol` and are absent from the application runtime dependency graph. Container operating-system packages require separate distribution review.

## Container pins

The Dockerfiles and Compose file use immutable registry digests, with descriptive version tags:

| Image | Digest |
| --- | --- |
| `maven:3.9.12-eclipse-temurin-25` | `sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e` |
| `eclipse-temurin:25-jdk` | `sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641` |
| `mcr.microsoft.com/playwright:v1.61.1-noble` | `sha256:5b8f294aff9041b7191c34a4bab3ac270157a28774d4b0660e9743297b697e48` |
| `nginx:1.30.6-alpine` | `sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951` |
| `quay.io/keycloak/keycloak:26.8.1` | `sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b` |
| `testcontainers/ryuk:0.14.0` | `sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0` |
| `postgres:18.6` | `sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6` |

The Maven image itself contains Temurin 25.0.2+10. The application Docker build overlays the pinned 25.0.4.1+1 JDK into the builder, so compilation and the deployed runtime use the same selected patch. Initial workspace investigation used the Maven image's existing 25.0.2+10 toolchain; test evidence must state the toolchain actually used.

The Keycloak fixture copies the pinned official distribution onto the pinned Temurin JDK because the official UBI9 runtime requires x86-64-v2 instructions unavailable on the qualification host. No Keycloak application code is changed. The production deployment does not contain Keycloak. Browser certificate tooling is pinned to Ubuntu `libnss3-tools=2:3.98-1ubuntu0.3`; generated certificates and trust databases exist only in disposable private fixture directories.

CI actions use immutable Git commit references in [ci.yml](../.github/workflows/ci.yml). Updating a digest or dependency is a reviewed repository change followed by the required suites; pins do not remove the need to apply security updates.

## Project license decision

The owner has not selected a license for this repository. No license is inferred from dependencies or neighboring projects. Selection of a project license and review of required notices is an unresolved public-distribution requirement. Engineering and local verification can continue; an open-source release must not be represented as complete until the owner resolves this decision.
