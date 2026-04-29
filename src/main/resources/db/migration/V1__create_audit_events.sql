CREATE TABLE audit_events (
    id            UUID        NOT NULL PRIMARY KEY,
    actor         VARCHAR(255) NOT NULL,
    action        VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255),
    resource_id   VARCHAR(255),
    details       TEXT,
    timestamp     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_events_actor     ON audit_events (actor);
CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp DESC);
