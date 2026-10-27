# Optional organization login through OIDC

The default deployment uses local accounts and works with no Keycloak container, LDAP, or external identity service. To enable organization login, configure an existing OpenID Connect provider. The application uses Spring Security OAuth2 Login; it does not embed Keycloak or accept arbitrary provider tokens as DAV credentials.

The identity boundary is:

```text
local password or verified upstream OIDC → internal account UUID → local permissions
                                       → local WebAuthn factor
                                       → this server's OAuth issuer / DAV credentials
```

## Configuration and provider registration

Create a confidential OIDC client at the provider, with authorization code flow enabled and the exact redirect URI `https://calendar.example/team/login/oauth2/code/upstream` for an application deployed at `https://calendar.example/team`. Require PKCE `S256`. Disable implicit, direct password grants, service accounts, and unused flows for that client. BusyStack requests `openid profile`, `prompt=login`, and `max_age=0`; registration uses the validated deployment URL, rather than request Host or Forwarded headers.

Add operator configuration, keeping the secret outside the image and repository:

```yaml
busystack:
  public-base-url: https://calendar.example/team
  issuer: https://calendar.example/team
  oidc:
    enabled: true
    issuer: https://identity.example/realms/organization
    client-id: busystack-calendar
    client-secret: ${BUSYSTACK_OIDC_CLIENT_SECRET}
    maximum-authentication-age: 24h
    allowed-users:
      - subject: "the-verified-immutable-provider-user-id"
        username: alice
        display-name: Alice
```

Obtain subjects from the identity provider's administrative records. They are not email addresses, display names, or necessarily usernames. In Keycloak the subject normally identifies the realm user by its immutable user ID. Each subject and internal username in the allowlist must be unique. Default provisioning is closed: an authenticated provider user outside the list is denied, unless an existing account owner has explicitly linked that identity through the protected linking flow.

The issuer must use HTTPS in production. The only HTTP exception is loopback with the explicitly enabled development configuration; there is no option to trust all certificates. Discovery uses Spring's standard issuer metadata implementation, deferred until an upstream login is attempted. Local startup and local login do not need an available provider. Invalid operator settings fail at startup; failed discovery or login fails closed without a local-password fallback for managed accounts.

Browser session cookies use `SameSite=Lax` to permit the top-level OIDC GET callback. CSRF protection remains enabled for browser mutations, including identity linking. The framework verifies authorization response state, ID-token signature, issuer, audience, and nonce. The application additionally requires a recent verified `auth_time` and the configured issuer/subject mapping.

## Accounts, linking, and MFA

New allowlisted users receive a stable internal UUID and no local password. They are ordinary users. Upstream realm roles, group claims, email, and preferred username do not grant BusyStack administration or select an existing account. Calendar permissions and administrative status are stored locally.

Linking an existing account requires recent MFA and an explicit POST from the account interface. The server stores a five-minute linking intent bound to the session and exact OAuth authorization request state, then requires fresh provider authentication. The verified callback binds the exact issuer and subject to the existing internal UUID. If the external identity already belongs to another account, or the account already has a different mapping, the operation fails. Matching names or email never trigger linking.

**Linking replaces local password sign-in for that account.** Its previous password is removed; existing calendar ownership and passkeys remain attached to the same UUID. Linking rotates the account security generation and revokes existing sessions, DAV credentials, and local OAuth grants. A locally bootstrapped administrator may deliberately link their own account after MFA, preserving their existing local administrative assignment. Realm administrators are never automatically application administrators.

Local users complete password plus WebAuthn MFA. Federated users complete a fresh upstream authorization-code login plus local WebAuthn MFA. Both factors must be recent for sensitive operations and local client authorization. An application-only trusted-upstream authority accompanies the typed upstream factor; ordinary DAV Bearer authentication does not receive those browser factors. WebAuthn account lookup supports enabled externally managed accounts, while the password provider rejects accounts without a local password. There is no fabricated password used to make federation work.

Upstream access and refresh tokens are discarded after login. This server's embedded OAuth authorization server remains the client-facing issuer, with its own registered clients, opaque tokens, scopes, audience, and revocation policy. Neither the upstream access token nor its ID token is a BusyStack DAV credential.

## Outages, disabling, and recovery

Local disabling immediately prevents subsequent browser, DAV, and local OAuth use according to the same account checks as local users. Independently issued credentials are also checked against durable external-authentication freshness. The maximum is 24 hours; operators may shorten it to at least one minute. A verified organization login refreshes that account's bound for all of its independently issued credentials. Once it expires, the user must sign in through the provider again. A passkey alone does not extend the upstream verification timestamp.

Upstream disabling or logout is **not an immediate push revocation** of BusyStack DAV credentials. This implementation does not consume Keycloak back-channel logout, SCIM, or provider-admin events. An upstream-disabled account may retain previously issued local access until the documented freshness limit, credential expiry, or local revocation, whichever occurs first. Removing an automatically provisioned subject from the configured allowlist denies further access when that updated configuration is loaded. Explicitly linked identities remain governed by their local account and freshness policy.

During an upstream outage, already issued access may continue inside its existing bound; the outage does not extend it. Local users can still authenticate. Federated accounts cannot fall back to a local password. Application logout clears the local session; it does not promise to terminate every upstream or device session. Fresh organization login always requests provider reauthentication.

Use provider-side recovery for a managed identity. The local offline password-reset command rejects externally managed accounts. An operator can use the separate offline external-account recovery command with the exact existing issuer and subject:

```sh
java -jar caldav-server.jar --spring.main.web-application-type=none \
  --busystack.recover-external-account=true \
  --busystack.recovery-username=alice \
  --busystack.recovery-issuer=https://identity.example/realms/organization \
  --busystack.recovery-subject=the-existing-immutable-subject
```

That command enables the selected existing account, removes its passkeys and independent credentials/grants, invalidates its sessions, and expires its upstream verification timestamp. It does not create a local password, change the mapping, or authenticate the user; fresh provider login remains required before client access resumes. A wrong issuer/subject pair fails without changing the account. Keep an independently recoverable local administrator for provider outages.

Changing the configured issuer does not relink old identities. Restoring or migrating a Keycloak realm must preserve the issuer/subject relationship or use a deliberate administrative migration. A matching email address is not a migration key.

## Verification

`KeycloakOidcIT` uses a disposable **Keycloak 26.8.1** container pinned to manifest digest `sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b`, actual HTTPS browser-form/code exchange, real signed ID tokens, application PostgreSQL, and application/provider HTTPS certificates signed by an isolated test CA. Both services bind only to loopback. The test fork trusts that CA with normal hostname verification, and restores its previous JVM trust settings afterward. On this workspace the official UBI9 image exits before Java because the CPU lacks x86-64-v2. The disposable [test Dockerfile](../qualification/keycloak/Dockerfile) therefore copies the unchanged distribution from that pinned image onto the same pinned Temurin 25 base used by the application; both image inputs remain fixed. The fixture allows up to five minutes for startup: augmentation and initial schema import exceeded an earlier three-minute bound on this shared legacy CPU, and that run correctly failed before executing provider cases. This timing is not an application workload measurement. [Keycloak supports OpenJDK 25](https://www.keycloak.org/server/supported-configurations). This is a provider test fixture, not an additional default deployment service. `ExternalIdentityIT` covers persistent mapping/linking rules separately. The explicit-link HTTP case seeds the initial MFA context and old OAuth grant rows, then exercises real CSRF enforcement, interleaved authorization callbacks, provider authentication, linking, grant revocation, and a replacement JDBC session. Those seeded preconditions do not qualify WebAuthn cryptography. `LinkingAuthorizationRequestResolverTest` checks that parallel authorization requests cannot consume another request's linking intent. The HTTP harness does not emulate browser SameSite enforcement, and its localhost ports are the same site; a cross-site browser callback is a separate, unexecuted qualification case. The combined upstream-login plus WebAuthn ceremony in an actual browser also remains unexecuted; local WebAuthn qualification and the seeded linking fixture do not establish that combined result. Executed on 2026-10-27 with Temurin 25.0.4.1 and Maven 3.9.12: `./mvnw -B -ntp spotless:apply verify -Dit.test=KeycloakOidcIT,ExternalIdentityIT` passed 17 unit tests and all nine selected integration tests, with zero failures, errors, or skips; SpotBugs reported zero high-priority findings. These results cover the listed HTTP/service cases, rather than those unexecuted browser cases.

Upstream references: [Spring Security OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html), [Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers), [Keycloak published version](https://www.keycloak.org/downloads), and [OIDC subject semantics](https://openid.net/specs/openid-connect-core-1_0.html#SubjectIDTypes).
