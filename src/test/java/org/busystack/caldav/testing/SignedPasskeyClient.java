package org.busystack.caldav.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real signed ES256/none-attestation ceremonies derived from the independent audit fixture. */
public final class SignedPasskeyClient implements AutoCloseable {
  public static final JsonMapper JSON = JsonMapper.builder().build();
  private final String base;
  private final String context;
  private final String origin;
  private final List<HttpClient> browsers = new java.util.ArrayList<>();

  public SignedPasskeyClient(int port, String context, String origin) {
    this.base = "https://127.0.0.1:" + port;
    this.context = context;
    this.origin = origin;
  }

  public HttpClient browser() {
    var browser =
        HttpClient.newBuilder()
            .sslContext(TestTls.instance().context())
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    browsers.add(browser);
    return browser;
  }

  @Override
  public void close() {
    browsers.forEach(HttpClient::close);
    browsers.clear();
  }

  public HttpResponse<String> request(
      HttpClient browser, String path, String method, String csrf, Object body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + context + path)).timeout(Duration.ofSeconds(30));
    if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
    String text =
        body == null ? "" : body instanceof String value ? value : JSON.writeValueAsString(body);
    request.header(
        "Content-Type",
        body instanceof String ? "application/x-www-form-urlencoded" : "application/json");
    return browser.send(
        request.method(method, HttpRequest.BodyPublishers.ofString(text)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> get(HttpClient browser, String path) throws Exception {
    return request(browser, path, "GET", null, null);
  }

  public HttpResponse<String> bearer(String path, String method, String token, String body)
      throws Exception {
    return browser()
        .send(
            HttpRequest.newBuilder(URI.create(base + context + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/calendar")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  public String csrf(HttpClient browser, String path) throws Exception {
    var response = get(browser, path);
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    return csrf(response.body());
  }

  public static String csrf(String html) {
    for (String pattern :
        List.of("name=\"csrf-token\" content=\"([^\"]+)\"", "name=\"_csrf\" value=\"([^\"]+)\"")) {
      var match = Pattern.compile(pattern).matcher(html);
      if (match.find()) return match.group(1);
    }
    throw new AssertionError("Response has no CSRF token");
  }

  public HttpClient passwordLogin(String username, String password) throws Exception {
    var browser = browser();
    String csrf = csrf(browser, "/login");
    var response =
        request(
            browser,
            "/login",
            "POST",
            csrf,
            form(Map.of("username", username, "password", password)));
    assertThat(response.statusCode()).isEqualTo(302);
    return browser;
  }

  public Credential enroll(HttpClient browser) throws Exception {
    return enroll(browser, "ES256");
  }

  public Credential enroll(HttpClient browser, String algorithm) throws Exception {
    var registration = registration(browser, "correct", algorithm);
    assertThat(registration.response().statusCode())
        .as(registration.response().body())
        .isEqualTo(200);
    return registration.credential();
  }

  public Registration registration(HttpClient browser, String mode) throws Exception {
    return registration(browser, mode, "ES256");
  }

  private Registration registration(HttpClient browser, String mode, String algorithm)
      throws Exception {
    String csrf = csrf(browser, "/security/passkeys");
    var response = request(browser, "/webauthn/register/options", "POST", csrf, null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode options = JSON.readTree(response.body());
    var generator =
        KeyPairGenerator.getInstance(
            algorithm.equals("RS256") ? "RSA" : algorithm.equals("EdDSA") ? "Ed25519" : "EC");
    if (algorithm.equals("RS256")) generator.initialize(2048);
    else if (algorithm.equals("ES256")) generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair key = generator.generateKeyPair();
    long coseAlgorithm = algorithm.equals("RS256") ? -257 : algorithm.equals("EdDSA") ? -8 : -7;
    assertThat(
            options
                .path("pubKeyCredParams")
                .valueStream()
                .anyMatch(value -> value.path("alg").asLong() == coseAlgorithm))
        .as(algorithm)
        .isTrue();
    byte[] cose;
    if (key.getPublic() instanceof RSAPublicKey rsa) {
      cose =
          concat(
              new byte[] {(byte) 0xa4, 1, 3, 3, 0x39, 1, 0, 0x20},
              bstr(unsigned(rsa.getModulus())),
              new byte[] {0x21},
              bstr(unsigned(rsa.getPublicExponent())));
    } else if (key.getPublic() instanceof EdECPublicKey ed) {
      byte[] compressed = fixed(ed.getPoint().getY());
      for (int low = 0, high = compressed.length - 1; low < high; low++, high--) {
        byte saved = compressed[low];
        compressed[low] = compressed[high];
        compressed[high] = saved;
      }
      if (ed.getPoint().isXOdd()) compressed[31] |= (byte) 0x80;
      cose = concat(new byte[] {(byte) 0xa4, 1, 1, 3, 0x27, 0x20, 6, 0x21}, bstr(compressed));
    } else {
      ECPublicKey ec = (ECPublicKey) key.getPublic();
      cose =
          concat(
              new byte[] {(byte) 0xa5, 1, 2, 3, 0x26, 0x20, 1, 0x21},
              bstr(fixed(ec.getW().getAffineX())),
              new byte[] {0x22},
              bstr(fixed(ec.getW().getAffineY())));
    }
    byte[] id = new byte[32];
    new SecureRandom().nextBytes(id);
    byte[] rpHash = hash(options.path("rp").path("id").asString().getBytes(StandardCharsets.UTF_8));
    byte[] authenticatorData =
        concat(
            mode.equals("wrong-rpidhash") ? new byte[32] : rpHash,
            new byte[] {
              (byte)
                  (mode.equals("no-up")
                      ? 0x4c
                      : mode.equals("no-uv")
                          ? 0x49
                          : mode.equals("impossible-backup") ? 0x55 : 0x4d)
            },
            new byte[4],
            new byte[16],
            new byte[] {0, 32},
            id,
            cose);
    byte[] attestation =
        concat(
            new byte[] {
              (byte) 0xa3,
              0x63,
              'f',
              'm',
              't',
              0x64,
              'n',
              'o',
              'n',
              'e',
              0x67,
              'a',
              't',
              't',
              'S',
              't',
              'm',
              't',
              (byte) 0xa0,
              0x68,
              'a',
              'u',
              't',
              'h',
              'D',
              'a',
              't',
              'a'
            },
            bstr(authenticatorData));
    byte[] clientData =
        JSON.writeValueAsBytes(
            Map.of(
                "type",
                "webauthn.create",
                "challenge",
                mode.equals("wrong-challenge")
                    ? encoded(new byte[32])
                    : options.path("challenge").asString(),
                "origin",
                mode.equals("wrong-origin") ? "https://attacker.invalid" : origin,
                "crossOrigin",
                false));
    var submitted =
        Map.of(
            "id", encoded(id),
            "rawId", encoded(id),
            "type", "public-key",
            "response",
                Map.of(
                    "attestationObject", encoded(attestation),
                    "clientDataJSON", encoded(clientData),
                    "transports", List.of("internal")),
            "clientExtensionResults", Map.of());
    Map<String, Object> body =
        Map.of("publicKey", Map.of("credential", submitted, "label", "signed-test-passkey"));
    var registered =
        mode.equals("prepare") ? null : request(browser, "/webauthn/register", "POST", csrf, body);
    return new Registration(
        new Credential(
            key,
            id,
            decoded(options.path("user").path("id").asString()),
            rpHash,
            algorithm.equals("RS256")
                ? "SHA256withRSA"
                : algorithm.equals("EdDSA") ? "Ed25519" : "SHA256withECDSA"),
        body,
        registered);
  }

  public Ceremony options(HttpClient browser) throws Exception {
    String csrf = csrf(browser, "/login");
    var response = request(browser, "/webauthn/authenticate/options", "POST", csrf, null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return new Ceremony(browser, csrf, JSON.readTree(response.body()));
  }

  public HttpResponse<String> assertCredential(
      Ceremony ceremony, Credential credential, String mode, int flags) throws Exception {
    return request(
        ceremony.browser(),
        "/login/webauthn",
        "POST",
        ceremony.csrf(),
        credential.assertion(ceremony.options(), origin, mode, flags));
  }

  public String localPath(String canonicalLocation) {
    URI location = URI.create(canonicalLocation);
    String path = location.getRawPath();
    assertThat(path).startsWith(context + "/");
    return path.substring(context.length())
        + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery());
  }

  public static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  public static Map<String, String> query(String uri) {
    var result = new LinkedHashMap<String, String>();
    for (String part : URI.create(uri).getRawQuery().split("&")) {
      var pair = part.split("=", 2);
      result.put(
          java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          pair.length == 1 ? "" : java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  public record Ceremony(HttpClient browser, String csrf, JsonNode options) {}

  public record Registration(
      Credential credential, Map<String, Object> body, HttpResponse<String> response) {}

  public static final class Credential {
    private final KeyPair key;
    private final byte[] id;
    private final byte[] handle;
    private final byte[] rpHash;
    private final String signatureAlgorithm;
    private int counter;

    private Credential(
        KeyPair key, byte[] id, byte[] handle, byte[] rpHash, String signatureAlgorithm) {
      this.key = key;
      this.id = id;
      this.handle = handle;
      this.rpHash = rpHash;
      this.signatureAlgorithm = signatureAlgorithm;
    }

    public String id() {
      return encoded(id);
    }

    public Map<String, Object> assertion(JsonNode options, String origin, String mode, int flags)
        throws Exception {
      var client = new HashMap<String, Object>();
      client.put("type", mode.equals("wrong-type") ? "webauthn.create" : "webauthn.get");
      client.put(
          "challenge",
          mode.equals("wrong-challenge")
              ? encoded(new byte[32])
              : options.path("challenge").asString());
      client.put("origin", mode.equals("wrong-origin") ? "https://attacker.invalid" : origin);
      client.put("crossOrigin", mode.equals("cross-origin") || mode.equals("untrusted-top-origin"));
      if (mode.equals("untrusted-top-origin")) client.put("topOrigin", "https://attacker.invalid");
      byte[] clientData = JSON.writeValueAsBytes(client);
      byte[] authenticatorData =
          concat(
              mode.equals("wrong-rpidhash") ? new byte[32] : rpHash,
              new byte[] {(byte) flags},
              ByteBuffer.allocate(4).putInt(++counter).array());
      var signer = Signature.getInstance(signatureAlgorithm);
      signer.initSign(key.getPrivate());
      signer.update(concat(authenticatorData, hash(clientData)));
      byte[] signature = signer.sign();
      if (mode.equals("invalid-signature")) signature[signature.length - 1] ^= 1;
      var response = new HashMap<String, Object>();
      response.put("authenticatorData", encoded(authenticatorData));
      response.put("clientDataJSON", encoded(clientData));
      response.put("signature", encoded(signature));
      response.put(
          "userHandle",
          mode.equals("absent")
              ? null
              : mode.equals("wrong") ? encoded(new byte[] {9, 8, 7}) : encoded(handle));
      return Map.of(
          "id",
          id(),
          "rawId",
          id(),
          "type",
          "public-key",
          "response",
          response,
          "clientExtensionResults",
          Map.of());
    }
  }

  public static String encoded(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decoded(String value) {
    return Base64.getUrlDecoder().decode(value);
  }

  public static byte[] hash(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static byte[] concat(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.writeBytes(array);
    return out.toByteArray();
  }

  private static byte[] fixed(BigInteger value) {
    byte[] encoded = value.toByteArray();
    byte[] result = new byte[32];
    System.arraycopy(
        encoded,
        Math.max(0, encoded.length - 32),
        result,
        Math.max(0, 32 - encoded.length),
        Math.min(32, encoded.length));
    return result;
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] result = value.toByteArray();
    return result.length > 1 && result[0] == 0
        ? java.util.Arrays.copyOfRange(result, 1, result.length)
        : result;
  }

  private static byte[] bstr(byte[] value) {
    byte[] header =
        value.length < 24
            ? new byte[] {(byte) (0x40 + value.length)}
            : value.length < 256
                ? new byte[] {0x58, (byte) value.length}
                : new byte[] {0x59, (byte) (value.length >> 8), (byte) value.length};
    return concat(header, value);
  }
}
