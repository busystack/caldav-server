# Your BusyStack account

BusyStack manages your account, collections and client access. Edit events, tasks and journal entries in your CalDAV application. Local authentication requires no identity
provider, email service or vendor account.

## First sign-in

Open the configured HTTPS address. Enter your account password or use
organization sign-in. On first sign-in, select **Create passkey**, then **Verify
and continue**. Returning users verify an existing passkey. After verification,
BusyStack opens Overview or the task that prompted sign-in.

A completed session supports ordinary navigation for up to 30 minutes of
inactivity and eight hours overall. Protected actions require both factors to
be under ten minutes old. BusyStack prompts when you initiate a protected
action and returns you to the task to review and submit again. Recovery codes
are available in [Security](account-security.md).

## Calendars, tasks & journals

Open **Calendars, tasks & journals** and choose **All**, **Owned by me** or
**Shared with me**. Search by name. Open a result for its details, access and
CalDAV address.

Use **Create a resource** to choose Calendar, Task list or Journal. Provide a
name and optional description and color. The type stays fixed. Metadata edits
preserve identity, address and stored resources. Use #RRGGBB or #RRGGBBAA for
the color property. A client can maintain its own local color preference.

Read/write recipients can edit shared metadata and import. Read-only recipients
can read and export accessible contents. Owners can manage sharing and delete
a resource. Confirming deletion removes its contents, history, bindings and
sharing grants. For important data, export or obtain an operator backup before
deletion. Reload a stale form and review the current state.

## Sharing and effective access

Open **Manage sharing** on an owned collection. Add a direct grant using the
recipient's exact username; no public account directory or autocomplete is
provided. Grant read-only or read and write, change a recorded grant, or remove it.
Group grants apply to every current and future member. Administrators maintain
membership, so granting a group delegates that membership decision.

The effective-permission table uses the same `collection_effective_members` view
as DAV authorization. Any read/write grant supplies read/write access. Removing a
direct grant does not remove access provided by a group; removing one group grant
does not remove another grant. Disabled accounts cannot authenticate, even if
membership remains recorded. Sharing changes can invalidate synchronization tokens;
clients must perform initial synchronization when a token is rejected.

## Connect an application

Open **Connect an app** and select your app. Use an existing application
password or create a separate one for this app or device. The one-time result
has the selected app’s setup steps, addresses, username, expiry and password.
Keep it open while setting up the app. The original password cannot be retrieved
afterward. To replace it, issue another, configure the client and revoke the
previous password when ready.

Copy the displayed server address, username and, when needed, collection address.
Addresses include the operator's configured subpath and never depend on request
Host or forwarding headers. Use normal TLS verification.

Thunderbird instructions under Connect an app follow the documented network-calendar
wizard. Historical qualification covers Thunderbird 159.0 and GNOME Calendar 50.0 /
EDS 3.56.2 on the exact 0.1.0 executable and configurations in the
[client record](observance-until-compatibility.md#qualified-client-configurations).
The historical GNOME record uses a source provisioned through EDS's supported API.
The GNOME 50.0 wizard has also passed a private-profile graphical setup and native
EDS read/write-permission check on the 0.2.0 development server. Use **Manage
Calendars → Add Calendar**, paste the portal's **GNOME connection address** under
**Connect to an Online Calendar**, supply the username and dedicated DAV credential
in **Credentials**, and choose **Connect**. When validation completes, choose **Add
Calendar**. Enter the same DAV credential in the native calendar-authentication
prompt when synchronization begins. The address includes only the username, never
the password: GNOME otherwise fails to retain the authentication user after
discovery. The qualification requires both imported UIDs, the recurrence exception,
extension data and native owner write permission; it does not claim full native
editor or assistive-technology coverage. Exact artifact/configuration results are
recorded separately.

Registered OAuth applications may use browser consent instead of a DAV credential.
Review them under **Security → Authorized applications**; see [OAuth](oauth.md).

## Security and data portability

Security links to passkeys, credentials, browser sessions, recovery and OAuth
grants. Local accounts can change their password. Organization-managed accounts
change primary credentials at their identity provider; there is no local-password
fallback. Local passkeys, credentials and permissions still belong to BusyStack.
Provider outages/freshness retain the [existing identity policy](keycloak.md).

Open a resource to **Import an iCalendar file** or export available contents. Use the [portability guide](portability.md) for conflict handling, limits and representation.

Ordinary form errors return to the editable form, preserve nonsensitive values and
associate fixed explanations with the affected controls. The focused validation
summary links to those controls. Passwords and issued secrets are never restored.
Duplicates return a conflict; stale forms require reloading the current state.
Authentication, authorization, capacity and operational failures retain their
separate error handling rather than masquerading as invalid field values.
