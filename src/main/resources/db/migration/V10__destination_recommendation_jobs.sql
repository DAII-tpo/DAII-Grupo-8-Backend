-- Durable recalculation requests; outbox insertion acknowledges the same request atomically.
CREATE TABLE destination_recommendation_jobs (
    event_id VARCHAR(64) PRIMARY KEY,
    request_token VARCHAR(36) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
