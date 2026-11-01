# Bedework runtime investigation

This isolated project is **not the BusyStack production protocol implementation**. It deliberately uses test-only account and backend fixtures to distinguish servlet/framework compatibility from full backend implementation. It is not included as a runtime dependency in the main application.

Run with Java 25 and the root Maven Wrapper:

```sh
./mvnw -B -ntp -s investigation/protocol/settings.xml -f investigation/protocol/pom.xml test
```

The settings file mirrors dependency repositories to Maven Central, including repositories declared transitively by Bedework. It never enables snapshots. If the user's global Maven cache has unrelated ownership restrictions, pass `-Dmaven.repo.local=/path/to/a/writable/probe-cache`.

The fixture registers the actual `CaldavBWServlet` in Spring Boot's embedded Tomcat and executes real HTTP with `java.net.http.HttpClient` through Spring Security's Basic filter and a precise DAV firewall method allowlist. The `SysIntf` fixture delegates to Mockito objects and has **no persistent backend**. A generated forwarding class is needed because the released servlet instantiates that interface implementation by configured class name and no-argument constructor.

Production permissions, storage, calendar parsing, credentials, and recurrence semantics cannot be inferred from this fixture. An expected-defect assertion records an upstream behavior that must be fixed before adoption; its passing result does not qualify that behavior for BusyStack.

See [ADR 0001](../../docs/adr/0001-protocol-engine.md) for evidence and the remaining decision gate.
