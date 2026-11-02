package org.busystack.caldav.security.passkey;

import org.busystack.caldav.security.session.SessionSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.transaction.annotation.Transactional;

/** Account binding and verified credential state around the supported Spring RP interface. */
public class AccountRelyingPartyOperations implements WebAuthnRelyingPartyOperations {
  private final WebAuthnRelyingPartyOperations delegate;
  private final AccountPasskeyUsers users;
  private final AccountPasskeyCredentials credentials;
  private final VerifiedCredentialRepository updates;
  private final UserDetailsService currentUsers;
  private final SessionSecurity sessions;

  AccountRelyingPartyOperations(
      WebAuthnRelyingPartyOperations delegate,
      AccountPasskeyUsers users,
      AccountPasskeyCredentials credentials,
      VerifiedCredentialRepository updates,
      UserDetailsService currentUsers,
      SessionSecurity sessions) {
    this.delegate = delegate;
    this.users = users;
    this.credentials = credentials;
    this.updates = updates;
    this.currentUsers = currentUsers;
    this.sessions = sessions;
  }

  @Override
  public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
      PublicKeyCredentialCreationOptionsRequest request) {
    return delegate.createPublicKeyCredentialCreationOptions(request);
  }

  @Override
  @Transactional
  public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    sessions.assertCurrent(authentication);
    var account = users.findByUsername(authentication.getName());
    if (account == null || !account.getId().equals(request.getCreationOptions().getUser().getId()))
      throw new PasskeyRegistrationRejectedException(
          "Registration does not belong to the ceremony account");
    if (credentials.findByCredentialId(request.getPublicKey().getCredential().getRawId()) != null)
      throw new PasskeyRegistrationRejectedException("Credential is already registered");
    try (var update = updates.verification(request.getPublicKey().getCredential().getRawId())) {
      var registered = delegate.registerCredential(request);
      var verified = update.verifiedRecord();
      if (!account.getId().equals(registered.getUserEntityUserId())
          || registered.isBackupState() && !registered.isBackupEligible())
        throw new PasskeyRegistrationRejectedException("Invalid registered credential state");
      credentials.save(verified);
      return registered;
    } catch (BadCredentialsException rejected) {
      throw new PasskeyRegistrationRejectedException(rejected);
    }
  }

  @Override
  public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
      PublicKeyCredentialRequestOptionsRequest request) {
    var options = delegate.createCredentialRequestOptions(request);
    // An identified account without credentials must not fall back to a discoverable assertion
    // for an unrelated account. The allow-list is kept in the framework's server-side session.
    if (identified(request.getAuthentication()) && options.getAllowCredentials().isEmpty())
      throw new BadCredentialsException("Account has no available passkey");
    return options;
  }

  @Override
  @Transactional
  public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
    var key = request.getPublicKey();
    var record = credentials.findByCredentialId(key.getRawId());
    if (record == null) throw new BadCredentialsException("Passkey unavailable");
    var owner = users.findById(record.getUserEntityUserId());
    if (owner == null) throw new BadCredentialsException("Passkey account unavailable");

    var handle = key.getResponse().getUserHandle();
    var allowed = request.getRequestOptions().getAllowCredentials();
    boolean accountIdentified = !allowed.isEmpty();
    if ((handle == null && !accountIdentified)
        || (handle != null && !handle.equals(owner.getId()))
        || (accountIdentified
            && allowed.stream().noneMatch(item -> key.getRawId().equals(item.getId()))))
      throw new BadCredentialsException("Passkey does not belong to the ceremony account");

    Authentication existing = SecurityContextHolder.getContext().getAuthentication();
    if (identified(existing)) {
      sessions.assertCurrent(existing);
      var expected = users.findByUsername(existing.getName());
      if (!accountIdentified || expected == null || !expected.getId().equals(owner.getId()))
        throw new BadCredentialsException("Passkey does not belong to the authenticated account");
    }
    // Check local/upstream state and hold the account generation stable through the state write.
    var current = currentUsers.loadUserByUsername(owner.getName());
    sessions.assertCurrent(
        UsernamePasswordAuthenticationToken.authenticated(
            current.getUsername(), null, current.getAuthorities()));

    try (var update = updates.verification(key.getRawId())) {
      var authenticated = delegate.authenticate(request); // One cryptographic verification only.
      if (!owner.getId().equals(authenticated.getId()))
        throw new BadCredentialsException("Passkey ownership changed");
      var verified = update.verifiedRecord();
      // These are exactly the authenticator-data bytes whose signature and flags the delegate
      // accepted above; no state is read from unverified data or saved on a rejected assertion.
      byte[] authenticatorData = key.getResponse().getAuthenticatorData().getBytes();
      if (authenticatorData.length < 37)
        throw new BadCredentialsException("Invalid authenticator data");
      int flags = Byte.toUnsignedInt(authenticatorData[32]);
      boolean backupEligible = (flags & 0x08) != 0;
      boolean backupState = (flags & 0x10) != 0;
      if (backupEligible != record.isBackupEligible() || backupState && !backupEligible)
        throw new BadCredentialsException("Invalid credential backup eligibility");
      credentials.save(
          ImmutableCredentialRecord.fromCredentialRecord(verified)
              .backupState(backupState)
              .uvInitialized(record.isUvInitialized() || (flags & 0x04) != 0)
              .build());
      return authenticated;
    }
  }

  private static boolean identified(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken);
  }
}
