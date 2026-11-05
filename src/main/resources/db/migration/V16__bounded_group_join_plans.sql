-- A plan cached while a group is small can retain one-row estimates after bulk
-- growth. Nested-loop scans in the binding INSERT then rescan the growing target
-- relation and can exceed the unchanged thirty-second mutation deadline.
-- These set-based equality joins support hash/merge plans. Scope the planner
-- preference to this first-party function; PostgreSQL restores the caller's
-- setting on both successful return and error. No permissions or data change.
ALTER FUNCTION dav_reconcile_group_memberships(uuid) SET enable_nestloop = off;
