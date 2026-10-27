# Groups and calendar sharing

An administrator with recent primary authentication and passkey verification manages local groups under **Administration → Sharing groups**. Groups have stable internal identifiers, a display name and up to 1,000 account members. The interface creates, renames, replaces membership and deletes groups. Deleting a group removes its collection grants. Account disabling still denies access even when its membership remains recorded.

A collection owner uses **Group sharing** on the account page to grant read-only or read/write access, or remove a grant. Sharing references the same authoritative collection. Administrators manage membership: granting a group therefore delegates control of its current and future membership. An administrator explicitly adding their own account receives that group's granted access; administrative status by itself does not bypass calendar permissions. Individual account grants and group grants combine to provide the effective access.

The sharing dropdown exposes group names and identifiers. DAV membership metadata is limited to members, owners of collections granted to the group, and administrators. Group administration, owned grants and effective calendar permissions are checked in the services as well as the HTTP layer. Mutations require current account state and recent MFA; browser forms retain CSRF protection.

Membership/grant changes reconcile shared collection bindings and change affected collection synchronization generations. A client with an invalidated token must perform an initial synchronization again. Removing group access preserves any access separately granted to the same account. Backup/restoration retains groups, grants, memberships and retired principal identifiers; operators must review these restored permissions before re-enabling accounts.

The directory supports at most 1,000 groups. V9 group service/protocol and real browser qualification results belong in the maintained conformance/evidence records; this workflow description is not an independent security assessment.
