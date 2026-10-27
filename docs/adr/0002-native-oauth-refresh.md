# ADR 0002: Public native clients need an application-owned refresh lifecycle

Date: 2026-10-27. Status: framework risk confirmed; persistent application implementation added; qualification in progress.

## Verified baseline

Maven Central publishes Spring Boot **4.1.2**, the newest stable 4.x release in the
metadata checked on this date; 4.2.0-RC1 is a prerelease. Its dependency BOM selects
Spring Security **7.1.2**, Spring Framework **7.0.10**, Tomcat **11.0.27**, PostgreSQL
JDBC **42.7.14**, Flyway **12.4.0**, and Testcontainers **2.0.5**. No independent
Spring Security version override is needed. Boot's documented Java range includes
Java 25, and its embedded Tomcat implements Jakarta Servlet 6.1.

Primary sources:

- [Published Boot versions](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml)
- [Exact Boot 4.1.2 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom)
- [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Security 7.1.2 source and Apache-2.0 license](https://github.com/spring-projects/spring-security/tree/7.1.2)

## Findings from the pinned implementation

Registering `authorization_code`, `refresh_token`, public client authentication
`none`, mandatory PKCE, and `reuseRefreshTokens(false)` does **not** establish a
working public-client refresh lifecycle:

1. `OAuth2RefreshTokenGenerator.generate` returns null for a public client's
   authorization-code exchange. `OAuth2AuthorizationCodeAuthenticationProvider`
   permits a custom generator to change that behavior.
2. `PublicClientAuthenticationConverter` only handles a PKCE code-exchange
   request. `PublicClientAuthenticationProvider` calls the required code-verifier
   authenticator. The stock pair cannot authenticate a subsequent public refresh
   request without a code verifier; a fake embedded client secret is inappropriate.
3. With rotation enabled, `OAuth2RefreshTokenAuthenticationProvider` replaces the
   authorization's single current refresh token and calls `save`. It does not
   retain a spent-token family ledger.
4. `JdbcOAuth2AuthorizationService` finds tokens using ordinary `SELECT` and
   updates the authorization by ID. It does not supply a row lock spanning
   validation and rotation, an optimistic version check, or spent-token history.
   A transaction around `save` alone cannot serialize the preceding check.

These are source findings, not a claim that Spring Security is unsuitable.
The relevant extension points are supported framework APIs:

- `OAuth2TokenGenerator<OAuth2RefreshToken>` for issuance.
- `OAuth2ClientAuthenticationConfigurer.authenticationConverter` and
  `authenticationProvider` for public refresh requests.
- `OAuth2TokenEndpointConfigurer.authenticationProviders` for wrapping/replacing
  grant processing at the transaction boundary.
- `OAuth2AuthorizationService` for persistent grant state.

References to the exact inspected version:

- [Refresh generator](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/token/OAuth2RefreshTokenGenerator.java)
- [Public-client request converter](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/web/authentication/PublicClientAuthenticationConverter.java)
- [Public-client provider](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/PublicClientAuthenticationProvider.java)
- [Code exchange provider](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2AuthorizationCodeAuthenticationProvider.java)
- [Refresh provider](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2RefreshTokenAuthenticationProvider.java)
- [JDBC authorization service](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/JdbcOAuth2AuthorizationService.java)
- [Client authentication extension points](https://github.com/spring-projects/spring-security/blob/7.1.2/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/authorization/OAuth2ClientAuthenticationConfigurer.java)
- [Protocol endpoint customization](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html)
- [Maintainer confirmation, issue 18793](https://github.com/spring-projects/spring-security/issues/18793)
- [Earlier customization discussion, issue 1430](https://github.com/spring-attic/spring-authorization-server/issues/1430)

## Decision

Keep public native clients public. Use Spring Security's authorization server,
PKCE validation, endpoint filters, and token generation contracts. A production
refresh implementation must add persistent token-family state and serialize
refresh validation, token consumption, successor creation, and grant update in a
single PostgreSQL transaction. Reuse of a spent token must durably revoke its
family, including issued access credentials according to the selected token model.
That revocation must commit even though the HTTP response is `invalid_grant`.
The account's enabled state and grant revocation must be checked inside that same
mutation boundary. Concurrent use of one refresh token must never yield two valid
successor chains.

Select opaque reference access tokens for the application. JDBC authorization
state and the refresh-family ledger permit revocation to apply on the next DAV
authentication without relying on access-token signing keys. Boot's default
startup-generated JWT/JWK auto-configuration is excluded. The isolated probe also
uses opaque tokens, but its test-only extensions lack replay protection and must
not be copied into production.

## Application implementation

`OAuthConfiguration` uses Spring's JDBC registration, authorization, and consent
stores, with PostgreSQL-specific schema types from migrations V2 and V3. The
application registers public native clients through a protected administration
page and requires S256, consent, and recent primary plus WebAuthn authentication. Primary
authentication is a local password or the configured, freshly verified upstream
OIDC login; arbitrary upstream tokens are not accepted for DAV.
Unused grants, response modes other than query, introspection metadata, signing-key, mTLS, and DPoP advertisements
are removed from the selected profile.

`FrameworkGrantBoundary` wraps the framework code, refresh, and revocation
providers. `RefreshFamilyStore` retains hashed spent tokens and acquires a
PostgreSQL per-client transaction lock with registration recheck, then a
per-authorization advisory transaction lock before account/family/grant mutation.
V7 enforces public-client-ID uniqueness and registered-client foreign keys. A code
exchange, refresh, revocation, reset and registration removal follow compatible
lock ordering; removing a client cannot leave a newly committed descendant grant. Family registration joins the code-exchange transaction; refresh executes
its validation, consumption, framework save, and successor registration together.
Replay revocation commits before `invalid_grant` is returned, even if a caller
subsequently rolls back an outer transaction.

`OAuthDavIntrospector` checks the current opaque token, issuer, DAV audience,
scope, account enabled state, recovery epoch, and family validity against durable
state on every DAV authentication. `OAuthClientService` and `OAuthGrantService`
enforce current account privileges and recent MFA for management mutations;
server-rendered pages retain browser CSRF protection.

## Evidence and outstanding acceptance

The executable investigation is [`investigation/oauth`](../../investigation/oauth/README.md).
It sends actual HTTP requests through embedded Tomcat and Spring Security, including
form login with CSRF and a session cookie. It exercises public S256 code exchange,
incorrect verifier, code replay, stock refresh rejection, supported extension
issuance/rotation, and the missing family-revocation control.

The isolated investigation passed four tests; its recorded result is
[`runtime-result.txt`](../../investigation/oauth/evidence/runtime-result.txt).
`RefreshFamilyStoreIT` and `OAuthHttpIT` separately exercise the application against
real PostgreSQL. The latter seeds completed interactive grants for most token
flows, explicitly distinguishing token/DAV qualification from browser/passkey
qualification. Its real password-only browser test must deny authorization.
The full application run on 2026-10-27 executed `OAuthHttpIT` **16/16** and
`RefreshFamilyStoreIT` **9/9**, as part of **84 unit +114 integration tests**, with
zero failures, errors, or skips and no SpotBugs findings. It exercised real
PostgreSQL 18.6 and embedded Tomcat. The full build command was:

```sh
./mvnw -B -ntp spotless:apply clean verify
```

Additional cases prove client-removal races, a consistent authorization/client
read snapshot, unsupported authentication rejection, account-reset/revocation
lock order and exact WebAuthn authentication serialization through a restricted
Spring-provided Jackson module. Follow-up protocol corrections have separate
focused reports; these counts describe the full-run baseline.

That run used Temurin Java 25.0.4.1 and Maven 3.9.12. The PostgreSQL fixture is
pinned to `postgres:18.6@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6`.
The per-class Failsafe reports, rather than the presence of test source, are the
execution evidence. Consult the overall final report for other suites in the
same command and any later changes.

Separate trusted-CA Chromium/CTAP2 browser profiles exercise actual passkey/MFA,
local consent, S256 exchange, refresh and revocation. Real HTTPS Keycloak tests
cover the optional upstream mode. Neither the framework probe nor seeded grants
qualify a physical authenticator or an independent native OAuth client; the
independent python-caldav and Thunderbird profiles use DAV credentials. Final
artifact, browser/proxy, recovery and restore evidence is recorded separately.
