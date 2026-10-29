package com.citypass.movilidad.service;

import com.citypass.movilidad.config.EmergencyProperties;
import com.citypass.movilidad.dto.EmergencyCreateRequest;
import com.citypass.movilidad.dto.EmergencyResponse;
import com.citypass.movilidad.dto.EmergencyTriageRequest;
import com.citypass.movilidad.event.EmergenciaBicicletaEvent;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeEmergency;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.BikeEmergencyRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Pedido de ayuda (SOS) durante un viaje.
 * <p>
 * Al pedir ayuda el viaje queda pausado (EMERGENCY) y la bici bloqueada. Primero hay una ventana para
 * cancelar (por si fue un error); después, un plazo para el cuestionario. Emergencias recibe un único
 * evento, EmergenciaBicicleta, cuando se envía el cuestionario o, si no llega, al vencer su plazo
 * (EmergencyDeadlineJob), con la prioridad del peor caso. Si se cancela a tiempo, no se avisa a nadie.
 */
@Service
@Transactional(readOnly = true)
public class EmergencyService {

    private static final Logger LOG = LoggerFactory.getLogger(EmergencyService.class);

    static final String EVENT_NAME = "EmergenciaBicicleta";

    // El código lleva la fecha local de la ciudad, que es la que ve el usuario.
    private static final ZoneId CODE_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(CODE_ZONE);

    private final BikeEmergencyRepository emergencyRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final BikeService bikeService;
    private final EventPublisher eventPublisher;
    private final EmergencyProperties properties;
    private final Clock clock;

    public EmergencyService(BikeEmergencyRepository emergencyRepository, TripRepository tripRepository,
                            UserRepository userRepository, BikeService bikeService, EventPublisher eventPublisher,
                            EmergencyProperties properties, Clock clock) {
        this.emergencyRepository = emergencyRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.bikeService = bikeService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Registra el pedido de ayuda: pausa el viaje y bloquea la bici. Todavía no avisa a Emergencias. */
    @Transactional
    public EmergencyResponse report(Long userId, Long tripId, EmergencyCreateRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
        // Un viaje de otro usuario se responde como inexistente.
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .filter(found -> found.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado: " + tripId));
        if (trip.getStatus() == TripStatus.EMERGENCY) {
            throw new BusinessRuleException("El viaje ya tiene una emergencia en curso: " + tripId);
        }
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new BusinessRuleException("El viaje no está activo: " + tripId);
        }
        Bike bike = bikeService.lockActiveBike(trip.getBike().getId());

        Instant now = clock.instant();
        Instant cancelableUntil = now.plus(properties.cancelWindow());
        BikeEmergency emergency = emergencyRepository.save(BikeEmergency.builder()
                .trip(trip)
                .user(user)
                .bike(bike)
                .status(EmergencyStatus.PENDING)
                .latitude(coordinate(request.latitude()))
                .longitude(coordinate(request.longitude()))
                .accuracyMeters(request.accuracyMeters() == null ? null
                        : BigDecimal.valueOf(request.accuracyMeters()).setScale(2, RoundingMode.HALF_UP))
                .reportedAt(now)
                .cancelableUntil(cancelableUntil)
                .triageDeadline(cancelableUntil.plus(properties.triageWindow()))
                .build());

        trip.setStatus(TripStatus.EMERGENCY);
        tripRepository.save(trip);
        bikeService.blockForEmergency(bike, user, "Emergencia " + codeOf(emergency));

        LOG.info("Pedido de ayuda registrado: emergencia={}, viaje={}", codeOf(emergency), tripId);
        return toResponse(emergency);
    }

    /** Cancela un SOS enviado por error, dentro de su ventana: el viaje sigue como si nada. */
    @Transactional
    public EmergencyResponse cancel(Long userId, Long emergencyId) {
        BikeEmergency emergency = lockOwned(userId, emergencyId);
        if (emergency.getStatus() != EmergencyStatus.PENDING) {
            throw new BusinessRuleException("La emergencia ya no se puede cancelar: " + codeOf(emergency));
        }
        Instant now = clock.instant();
        if (!now.isBefore(emergency.getCancelableUntil())) {
            throw new BusinessRuleException("Venció el plazo para cancelar la emergencia: " + codeOf(emergency));
        }
        emergency.setStatus(EmergencyStatus.CANCELLED);
        emergency.setCancelledAt(now);

        Long tripId = emergency.getTrip().getId();
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado: " + tripId));
        trip.setStatus(TripStatus.ACTIVE);
        tripRepository.save(trip);
        Bike bike = bikeService.lockActiveBike(emergency.getBike().getId());
        bikeService.unblockAfterCancelledEmergency(bike, emergency.getUser(),
                "Emergencia " + codeOf(emergency) + " cancelada");

        LOG.info("Pedido de ayuda cancelado: emergencia={}", codeOf(emergency));
        return toResponse(emergencyRepository.save(emergency));
    }

    /** Registra las respuestas del cuestionario y avisa a Emergencias. */
    @Transactional
    public EmergencyResponse submitTriage(Long userId, Long emergencyId, EmergencyTriageRequest request) {
        BikeEmergency emergency = lockOwned(userId, emergencyId);
        if (emergency.getStatus() != EmergencyStatus.PENDING) {
            throw new BusinessRuleException("La emergencia ya fue enviada o cancelada: " + codeOf(emergency));
        }
        Instant now = clock.instant();
        if (now.isBefore(emergency.getCancelableUntil())) {
            throw new BusinessRuleException("La emergencia todavía se puede cancelar: " + codeOf(emergency));
        }
        emergency.setCanMove(request.canMove());
        emergency.setBleeding(request.bleeding());
        emergency.setVehicleInvolved(request.vehicleInvolved());
        sendToEmergencias(emergency, now);
        return toResponse(emergencyRepository.save(emergency));
    }

    /**
     * Si venció el plazo del cuestionario sin respuesta, avisa a Emergencias asumiendo el peor caso.
     * Devuelve false si la emergencia ya se había resuelto de otra forma (cancelada o con cuestionario).
     */
    @Transactional
    public boolean sendIfTriageExpired(Long emergencyId) {
        BikeEmergency emergency = emergencyRepository.findByIdForUpdate(emergencyId).orElse(null);
        Instant now = clock.instant();
        if (emergency == null || emergency.getStatus() != EmergencyStatus.PENDING
                || now.isBefore(emergency.getTriageDeadline())) {
            return false;
        }
        sendToEmergencias(emergency, now);
        emergencyRepository.save(emergency);
        return true;
    }

    /** Emergencias pendientes cuyo plazo para el cuestionario ya venció. */
    public List<Long> findExpiredPendingIds() {
        return emergencyRepository.findIdsByStatusAndTriageDeadlineBefore(EmergencyStatus.PENDING, clock.instant());
    }

    /** Emergencia en curso del usuario (su viaje sigue pausado), para retomar la pantalla al recargar. */
    public Optional<EmergencyResponse> findCurrent(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Usuario no encontrado: " + userId);
        }
        return emergencyRepository.findFirstByUserIdAndStatusInOrderByReportedAtDescIdDesc(
                        userId, EnumSet.of(EmergencyStatus.PENDING, EmergencyStatus.REPORTED))
                .filter(emergency -> emergency.getTrip().getStatus() == TripStatus.EMERGENCY)
                .map(this::toResponse);
    }

    private void sendToEmergencias(BikeEmergency emergency, Instant now) {
        emergency.setPriority(EmergencyPriorityPolicy.suggest(
                emergency.getCanMove(), emergency.getBleeding(), emergency.getVehicleInvolved()));
        emergency.setStatus(EmergencyStatus.REPORTED);
        emergency.setSentAt(now);
        // Va al outbox en esta misma transacción: el evento sale si y solo si la emergencia quedó REPORTED.
        eventPublisher.publish(EVENT_NAME, AggregateType.TRIP, emergency.getTrip().getId(), toEvent(emergency));
        LOG.info("Emergencia enviada a Emergencias: emergencia={}, prioridad={}",
                codeOf(emergency), emergency.getPriority());
    }

    private BikeEmergency lockOwned(Long userId, Long emergencyId) {
        return emergencyRepository.findByIdForUpdate(emergencyId)
                .filter(found -> found.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Emergencia no encontrada: " + emergencyId));
    }

    /** Código legible: EMG-yyyyMMdd-NNNN, con la fecha local del pedido y el id. */
    static String codeOf(BikeEmergency emergency) {
        return "EMG-" + CODE_DATE.format(emergency.getReportedAt()) + "-" + String.format("%04d", emergency.getId());
    }

    private static BigDecimal coordinate(Double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private EmergenciaBicicletaEvent toEvent(BikeEmergency emergency) {
        Trip trip = emergency.getTrip();
        Bike bike = emergency.getBike();
        Station origin = trip.getOriginStation();
        User user = emergency.getUser();
        boolean answered = emergency.getCanMove() != null;
        return new EmergenciaBicicletaEvent(
                codeOf(emergency),
                user.getExternalUserId(),
                emergency.getReportedAt().toEpochMilli(),
                new EmergenciaBicicletaEvent.Ubicacion(emergency.getLatitude().doubleValue(),
                        emergency.getLongitude().doubleValue(),
                        emergency.getAccuracyMeters() == null ? null : emergency.getAccuracyMeters().doubleValue()),
                new EmergenciaBicicletaEvent.Triage(answered, emergency.getCanMove(), emergency.getBleeding(),
                        emergency.getVehicleInvolved(), emergency.getPriority()),
                new EmergenciaBicicletaEvent.Viaje(trip.getId(), trip.getStartedAt().toEpochMilli(),
                        origin.getId(), origin.getName(), bike.getId(), bike.getCode()),
                new EmergenciaBicicletaEvent.Usuario(user.getId(), user.getFirstName(), user.getLastName(),
                        user.getEmail()));
    }

    private EmergencyResponse toResponse(BikeEmergency emergency) {
        Bike bike = emergency.getBike();
        return new EmergencyResponse(emergency.getId(), codeOf(emergency), emergency.getStatus(),
                emergency.getTrip().getId(), bike.getId(), bike.getCode(),
                emergency.getLatitude(), emergency.getLongitude(), emergency.getAccuracyMeters(),
                emergency.getReportedAt(), emergency.getCancelableUntil(), emergency.getTriageDeadline(),
                properties.cancelWindow().toSeconds(), properties.triageWindow().toSeconds(),
                emergency.getCanMove(), emergency.getBleeding(), emergency.getVehicleInvolved(),
                emergency.getPriority(), emergency.getSentAt());
    }
}
