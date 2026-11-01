# Native OAuth refresh framework probe

This isolated Maven project tests Spring Boot 4.1.2 / Spring Security 7.1.2 through
a real embedded HTTP server. All accounts, registrations, grants, passwords, and
keys in this project are disposable **test fixtures**. None is production account
management or a production refresh implementation.

Run with Java 25 and Maven 3.9.12:

```sh
mvn -B -ntp -f investigation/oauth/pom.xml test
```

`NativeOAuthRiskTest` signs in through Spring's generated form login, preserving
CSRF protection and session cookies. A registered public client completes S256
PKCE and obtains an opaque access token, but receives no refresh token from the
stock generator. The test also verifies incorrect-verifier rejection, code replay
rejection, and rejection of a stock public refresh request.

`CustomizedRefreshRiskTest` enables test-only supported extension points for
refresh issuance and public client identification. It proves issuance and rotation
can work without a fake native client secret. It then replays a spent token and
shows that the current descendant remains usable: **this passing test demonstrates
a missing application security control, not a passing production security test**.

The extensions in `PublicRefreshExtensions` deliberately lack durable token-family
replay/concurrency handling. Never use them in the production application. See
[ADR 0002](../../docs/adr/0002-native-oauth-refresh.md) for required production work.

Results must be read from `target/surefire-reports` after execution. No Docker or
database is involved in this narrow framework investigation. It does not qualify
production persistence, HTTPS, refresh concurrency, expiration, or account disabling.
