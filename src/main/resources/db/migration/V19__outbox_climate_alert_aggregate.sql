-- El evento AlertaClimaticaAplicada/Levantada usa CLIMATE_ALERT como agregado.
-- V12 todavía limita event_outbox a los tipos previos; ampliar el CHECK sin perderlos.
ALTER TABLE event_outbox DROP CHECK chk_event_outbox_aggregate_type;
ALTER TABLE event_outbox ADD CONSTRAINT chk_event_outbox_aggregate_type
    CHECK (aggregate_type IN ('BIKE','TRIP','STATION','INCIDENT','CULTURAL_EVENT','CLIMATE_ALERT'));
