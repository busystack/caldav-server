-- GroupService holds the exclusive DAV mutation lock and affected collection locks in UUID
-- order. Materialize the affected/effective sets once per statement: a correlated predicate
-- over the grouped effective-membership view can otherwise aggregate that view per binding.
-- Group maintenance and namespace preflight start with collection IDs. The existing
-- home-first uniqueness index cannot serve that access path under generic query plans.
CREATE INDEX dav_bindings_collection_home ON dav_bindings(collection_id,home_id)
    WHERE collection_id IS NOT NULL;

CREATE FUNCTION dav_reconcile_group_memberships(target_group uuid) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    WITH affected AS MATERIALIZED (
        SELECT c.id,c.owner_id FROM collections c
        JOIN collection_group_members grant_row ON grant_row.collection_id=c.id
        WHERE grant_row.group_id=target_group
    ), effective AS MATERIALIZED (
        -- This is the membership-key projection of collection_effective_members. Read/write
        -- precedence remains in that view; binding existence needs only these distinct keys.
        SELECT direct.collection_id,direct.account_id FROM affected a
        JOIN collection_members direct ON direct.collection_id=a.id
        WHERE direct.account_id<>a.owner_id
        UNION
        SELECT granted.collection_id,member.account_id FROM affected a
        JOIN collection_group_members granted ON granted.collection_id=a.id
        JOIN sharing_group_members member ON member.group_id=granted.group_id
        WHERE member.account_id<>a.owner_id
    ), missing AS MATERIALIZED (
        SELECT e.collection_id,e.account_id FROM effective e
        EXCEPT
        SELECT b.collection_id,b.home_id FROM dav_bindings b JOIN affected a ON a.id=b.collection_id
    )
    INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
    SELECT m.account_id,
        CASE WHEN occupied.home_id IS NOT NULL
            THEN c.id::text||'-'||gen_random_uuid()::text ELSE c.id::text END,
        c.id,c.name
    FROM missing m JOIN collections c ON c.id=m.collection_id
    LEFT JOIN dav_bindings occupied
        ON occupied.home_id=m.account_id AND occupied.path=c.id::text;

    WITH affected AS MATERIALIZED (
        SELECT c.id,c.owner_id FROM collections c
        JOIN collection_group_members grant_row ON grant_row.collection_id=c.id
        WHERE grant_row.group_id=target_group
    ), effective AS MATERIALIZED (
        -- This is the membership-key projection of collection_effective_members. Read/write
        -- precedence remains in that view; binding existence needs only these distinct keys.
        SELECT direct.collection_id,direct.account_id FROM affected a
        JOIN collection_members direct ON direct.collection_id=a.id
        WHERE direct.account_id<>a.owner_id
        UNION
        SELECT granted.collection_id,member.account_id FROM affected a
        JOIN collection_group_members granted ON granted.collection_id=a.id
        JOIN sharing_group_members member ON member.group_id=granted.group_id
        WHERE member.account_id<>a.owner_id
    ), removed AS MATERIALIZED (
        -- Both membership keys are NOT NULL in the underlying grant tables. The uncorrelated
        -- NOT IN uses one membership hash; carrying the complete primary key avoids a stale
        -- generic plan scanning every binding in a home for each collection being removed.
        SELECT b.home_id,b.path FROM dav_bindings b JOIN affected a ON a.id=b.collection_id
        WHERE b.home_id<>a.owner_id
            AND (b.collection_id,b.home_id) NOT IN (
                SELECT e.collection_id,e.account_id FROM effective e
            )
    )
    DELETE FROM dav_bindings b USING removed r
    WHERE b.home_id=r.home_id AND b.path=r.path;

    UPDATE collections c SET generation=gen_random_uuid()
    FROM collection_group_members affected
    WHERE affected.group_id=target_group AND affected.collection_id=c.id;
END $$;
