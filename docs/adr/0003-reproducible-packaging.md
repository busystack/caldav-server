# ADR 0003: Fix the build JVM timezone for reproducible executable archives

Date: 2026-10-27. Status: accepted; isolated configuration and final host/container artifact equality verified.

## Evidence

The same application source produced host and Docker executable JARs with identical entry names, order, content bytes, compression methods, permissions and ordinary ZIP timestamps. Their SHA-256 values differed because 283 `BOOT-INF/classes` entries contained different NTFS timestamp extra fields. Decoding the first field gave `2026-10-27T07:00:00Z` on the Vancouver host and `2026-10-27T00:00:00Z` in the UTC container. Both ordinary ZIP timestamps were `2026-10-27T00:00:00`. The plain, pre-repackage JAR had no extra fields.

This is a build-time timezone leak, rather than a difference in calendar data or Java compilation. The pinned Spring Boot 4.1.2 repackager transforms application entries and copies their file-time metadata. Its archive writer normalizes the ordinary timestamp using the configured output timestamp; the NTFS metadata still reflects the build JVM timezone. The relevant implementation is in `Packager.RepackagingEntryTransformer` and `JarWriter` in the published [Boot loader-tools 4.1.2 source archive](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-loader-tools/4.1.2/spring-boot-loader-tools-4.1.2-sources.jar).

## Decision

Retain the pinned Spring framework and packaging plugin versions. Keep the fixed `project.build.outputTimestamp`, and configure the Maven JVM through the supported project file `.mvn/jvm.config`:

```text
-Duser.timezone=UTC
```

[Maven documents this file as project JVM configuration](https://maven.apache.org/configure.html#a.mvn.2Fjvm.config_file.3A). [Boot documents `outputTimestamp` for executable packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html#packaging.repackage-goal.parameter-details.outputTimestamp). These settings define the build environment. They do not convert stored iCalendar values, floating dates, application runtime configuration or user time zones.

No custom ZIP rewriting, timestamp-stripping code or framework dependency override is introduced.

## Verification

An isolated minimal application used Java 25.0.4.1, Maven 3.9.12 and Boot 4.1.2, with the same fixed output timestamp. Maven JAR Plugin 3.4.2 and the current stable 3.5.1 both exhibited the same Vancouver/UTC difference. Upgrading the JAR plugin alone therefore did not fix the Boot repackage output. Apache's [plugin listing](https://maven.apache.org/plugins/) and the [published 3.5.1 POM](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.5.1/maven-jar-plugin-3.5.1.pom) establish that the latter was a stable release; no beta was adopted.

The isolated executable JAR results were:

| JAR plugin | JVM timezone | SHA-256 |
| --- | --- | --- |
|3.4.2|America/Vancouver|`b8e5711424355b62fc760bd4063d41c47acbbb82a3e087441cb84b120d63b1ce`|
|3.4.2|UTC|`1f08affbeae435d0190bf973ff75a281a11fdea12425780218a64464f54ea899`|
|3.5.1|America/Vancouver|`04f22edb900d44b8e6ce6f15726ce42bdc99113ede19d34743bb46cbe59fa037`|
|3.5.1|UTC|`402a3eb72cee56065da0137882a9537109477f8a5e7c38941285bbf54b1e6801`|

A clean Plugin 3.4.2 rebuild under host `TZ=America/Vancouver`, with no timezone in `MAVEN_OPTS` and only the project JVM file above, exactly reproduced the UTC row's bytes and SHA-256. Plugin versions are compared within their own rows: their manifest metadata naturally differs across versions.

The final application executable JAR built on the Vancouver host with the project JVM configuration is byte-for-byte identical to `/opt/busystack/application.jar` extracted from independently built image `sha256:461ca83649a45d8ca37ba1570584f567c0bbc95279b14a6662edfc9ce6987e89`. Both artifacts have SHA-256 `8c043227a66100baddafbcf8cf3b0beac748cc69d74a1811359bac07d05db4a6`; all 513 ZIP entries and the complete archive bytes match. This verifies the pinned build in those two Linux environments, following Maven's [independent-environment verification guidance](https://maven.apache.org/guides/mini/guide-reproducible-builds.html).
