package com.citypass.movilidad.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BI Caso 4. Cada incidente creado/resuelto genera un snapshot global;
 * adicionalmente cada 15 minutos para mantener el indicador actualizado
 * ante cambios en el catálogo de estaciones.
 */
@Component
public class AffectedStationsMetricsPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(AffectedStationsMetricsPublisher.class);

    private final AffectedStationsMetricsService metrics;
    private final AffectedStationsEventWriter events;

    public AffectedStationsMetricsPublisher(AffectedStationsMetricsService metrics,
                                            AffectedStationsEventWriter events) {
        this.metrics = metrics;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentChanged(TrafficIncidentChanged ignored) {
        publishSafely();
    }

    @Scheduled(cron = "${movilidad.analytics.affected-stations.cron:0 */15 * * * *}")
    public void refresh() {
        publishSafely();
    }

    private void publishSafely() {
        try {
            publish();
        } catch (RuntimeException exception) {
            LOG.error("No se pudo actualizar la métrica de estaciones afectadas en EDA", exception);
        }
    }

    public void publish() {
        AffectedStationsMetricsService.Snapshot snapshot = metrics.calculate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cantidadEstacionesAfectadas", snapshot.affectedStations());
        data.put("calculadaEn", Instant.now().toString());
        events.publish(data);
    }
}
