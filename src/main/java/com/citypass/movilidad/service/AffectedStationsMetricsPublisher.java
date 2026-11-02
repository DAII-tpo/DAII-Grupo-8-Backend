package com.citypass.movilidad.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


/** Reconciliación al confirmar incidentes y cada 15 minutos para recuperar fallos/cambios del catálogo. */
@Component
public class AffectedStationsMetricsPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(AffectedStationsMetricsPublisher.class);
    private final AffectedStationsService affectations;

    public AffectedStationsMetricsPublisher(AffectedStationsService affectations) {
        this.affectations = affectations;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentChanged(TrafficIncidentChanged ignored) { refresh(); }

    @Scheduled(cron = "${movilidad.analytics.affected-stations.cron:0 */15 * * * *}")
    public void refresh() {
        try {
            publish();
        } catch (RuntimeException exception) {
            LOG.error("No se pudieron actualizar las afectaciones de estaciones en EDA; se reintentará", exception);
        }
    }

    public void publish() { affectations.synchronize(); }
}
