-- Query API (design.md §7): one composite index per supported filter combination,
-- each trailing in (timestamp, id) so the half-open range scan and the
-- ORDER BY timestamp ASC, id ASC / keyset boundary need no extra sort node.

CREATE INDEX idx_audit_events_timestamp_id
    ON audit_events (timestamp, id);

CREATE INDEX idx_audit_events_actor_timestamp_id
    ON audit_events (actor, timestamp, id);

CREATE INDEX idx_audit_events_resource_type_timestamp_id
    ON audit_events (resource_type, timestamp, id);

CREATE INDEX idx_audit_events_resource_id_timestamp_id
    ON audit_events (resource_id, timestamp, id);

-- V1 indexes superseded by the above:
--   idx_audit_events_actor      -> covered by idx_audit_events_actor_timestamp_id
--   idx_audit_events_timestamp  -> (timestamp DESC) replaced by ascending idx_audit_events_timestamp_id
DROP INDEX idx_audit_events_actor;
DROP INDEX idx_audit_events_timestamp;
