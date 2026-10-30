package com.citypass.movilidad.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Avisa a Emergencias de los pedidos de ayuda cuyo cuestionario no llegó a tiempo. Corre en el servidor
 * para que el aviso salga aunque la app se haya cerrado o perdido conexión.
 */
@Component
public class EmergencyDeadlineJob {

    private static final Logger LOG = LoggerFactory.getLogger(EmergencyDeadlineJob.class);

    private final EmergencyService emergencyService;

    public EmergencyDeadlineJob(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    @Scheduled(fixedDelayString = "${movilidad.emergency.deadline-check-interval:5s}")
    public void sendExpired() {
        for (Long id : emergencyService.findExpiredPendingIds()) {
            try {
                emergencyService.sendIfTriageExpired(id);
            } catch (RuntimeException ex) {
                // Una emergencia que falla no frena a las demás; se reintenta en la próxima pasada.
                LOG.error("No se pudo enviar la emergencia vencida id={}", id, ex);
            }
        }
    }
}
