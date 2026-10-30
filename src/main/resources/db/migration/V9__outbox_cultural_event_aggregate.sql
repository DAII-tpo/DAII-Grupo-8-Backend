-- The Java enum already includes CULTURAL_EVENT; retain all existing aggregate types.
ALTER TABLE event_outbox DROP CHECK chk_event_outbox_aggregate_type;
ALTER TABLE event_outbox ADD CONSTRAINT chk_event_outbox_aggregate_type
    CHECK (aggregate_type IN ('BIKE','TRIP','STATION','INCIDENT','CULTURAL_EVENT'));
