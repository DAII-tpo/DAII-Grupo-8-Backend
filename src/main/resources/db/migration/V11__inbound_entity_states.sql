-- Retain terminal states even when creation arrives later on another topic.
CREATE TABLE inbound_entity_states (
    entity_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    state_value VARCHAR(16) NOT NULL,
    PRIMARY KEY (entity_type, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve previously projected terminal/disabled states during upgrade.
INSERT INTO inbound_entity_states (entity_type, external_id, state_value)
SELECT 'CULTURAL_EVENT', event_id, 'CANCELLED' FROM cultural_destinations WHERE cancelled = TRUE;
INSERT INTO inbound_entity_states (entity_type, external_id, state_value)
SELECT DISTINCT 'SPACE', space_id, 'DISABLED' FROM cultural_destinations WHERE space_enabled = FALSE;
INSERT INTO inbound_entity_states (entity_type, external_id, state_value)
SELECT source, external_id, 'CLOSED' FROM city_incidents WHERE active = FALSE;
