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
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.EmergencyPriority;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.BikeEmergencyRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmergencyServiceTest {

    // 02:00 UTC del 29/10 es todavía el 28/10 en Buenos Aires.
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
    private static final EmergencyProperties WINDOWS =
            new EmergencyProperties(Duration.ofSeconds(10), Duration.ofSeconds(60));

    private BikeEmergencyRepository emergencyRepository;
    private TripRepository tripRepository;
    private UserRepository userRepository;
    private BikeService bikeService;
    private EventPublisher eventPublisher;

    private User user;
    private Bike bike;
    private Trip trip;

    @BeforeEach
    void setUp() {
        emergencyRepository = mock(BikeEmergencyRepository.class);
        tripRepository = mock(TripRepository.class);
        userRepository = mock(UserRepository.class);
        bikeService = mock(BikeService.class);
        eventPublisher = mock(EventPublisher.class);
        when(emergencyRepository.save(any(BikeEmergency.class))).thenAnswer(invocation -> {
            BikeEmergency emergency = invocation.getArgument(0);
            if (emergency.getId() == null) {
                emergency.setId(91L);
            }
            return emergency;
        });

        user = User.builder().id(1L).firstName("Ana").lastName("Pérez").email("ana@example.com")
                .externalUserId("sub-123").build();
        bike = Bike.builder().id(5L).code("BIKE-03520").status(BikeStatus.IN_USE).build();
        Station origin = Station.builder().id(10L).name("11 de Septiembre").build();
        trip = Trip.builder().id(7L).user(user).bike(bike).originStation(origin)
                .startedAt(NOW.minusSeconds(600)).status(TripStatus.ACTIVE).build();
    }

    @Test
    void pedirAyudaPausaElViajeBloqueaLaBiciYAbreLosPlazosSinAvisarTodavia() {
        givenUserAndTrip();

        EmergencyResponse response = service(NOW).report(1L, 7L,
                new EmergencyCreateRequest(-34.60371234, -58.3816, 12.345));

        assertThat(response.status()).isEqualTo(EmergencyStatus.PENDING);
        assertThat(response.code()).isEqualTo("EMG-20261028-0091");
        assertThat(response.latitude()).isEqualByComparingTo("-34.603712");
        assertThat(response.accuracyMeters()).isEqualByComparingTo("12.35");
        assertThat(response.cancelableUntil()).isEqualTo(NOW.plusSeconds(10));
        assertThat(response.triageDeadline()).isEqualTo(NOW.plusSeconds(70));
        assertThat(response.cancelWindowSeconds()).isEqualTo(10);
        assertThat(response.triageWindowSeconds()).isEqualTo(60);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.EMERGENCY);
        verify(bikeService).blockForEmergency(bike, user, "Emergencia EMG-20261028-0091");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rechazaPedirAyudaEnUnViajeAjenoONoActivo() {
        givenUserAndTrip();
        EmergencyService service = service(NOW);
        EmergencyCreateRequest location = new EmergencyCreateRequest(-34.6, -58.38, null);

        when(tripRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(
                Trip.builder().id(8L).user(User.builder().id(2L).build()).status(TripStatus.ACTIVE).build()));
        assertThatThrownBy(() -> service.report(1L, 8L, location)).isInstanceOf(ResourceNotFoundException.class);

        trip.setStatus(TripStatus.EMERGENCY);
        assertThatThrownBy(() -> service.report(1L, 7L, location))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ya tiene una emergencia");

        trip.setStatus(TripStatus.COMPLETED);
        assertThatThrownBy(() -> service.report(1L, 7L, location))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("no está activo");
        verify(emergencyRepository, never()).save(any());
    }

    @Test
    void cancelarDentroDeLaVentanaRetomaElViajeYDesbloqueaLaBici() {
        BikeEmergency emergency = givenPending();
        trip.setStatus(TripStatus.EMERGENCY);
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);

        EmergencyResponse response = service(NOW.plusSeconds(9)).cancel(1L, 91L);

        assertThat(response.status()).isEqualTo(EmergencyStatus.CANCELLED);
        assertThat(emergency.getCancelledAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        verify(bikeService).unblockAfterCancelledEmergency(bike, user, "Emergencia EMG-20261028-0091 cancelada");
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void noSePuedeCancelarFueraDeLaVentanaNiUnaEmergenciaAjenaOResuelta() {
        BikeEmergency emergency = givenPending();

        assertThatThrownBy(() -> service(NOW.plusSeconds(10)).cancel(1L, 91L))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Venció el plazo");
        assertThatThrownBy(() -> service(NOW).cancel(2L, 91L)).isInstanceOf(ResourceNotFoundException.class);

        emergency.setStatus(EmergencyStatus.REPORTED);
        assertThatThrownBy(() -> service(NOW).cancel(1L, 91L)).isInstanceOf(BusinessRuleException.class);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
    }

    @Test
    void elCuestionarioAvisaAEmergenciasConUnUnicoEventoCompleto() {
        BikeEmergency emergency = givenPending();

        EmergencyResponse response = service(NOW.plusSeconds(15))
                .submitTriage(1L, 91L, new EmergencyTriageRequest(true, false, false));

        assertThat(response.status()).isEqualTo(EmergencyStatus.REPORTED);
        assertThat(response.priority()).isEqualTo(EmergencyPriority.P3);
        assertThat(emergency.getSentAt()).isEqualTo(NOW.plusSeconds(15));

        EmergenciaBicicletaEvent event = publishedEvent();
        assertThat(event.emergenciaId()).isEqualTo("EMG-20261028-0091");
        assertThat(event.actorSub()).isEqualTo("sub-123");
        assertThat(event.reportadaEn()).isEqualTo(NOW.toEpochMilli());
        assertThat(event.ubicacion()).isEqualTo(new EmergenciaBicicletaEvent.Ubicacion(-34.6037, -58.3816, 12.5));
        assertThat(event.triage()).isEqualTo(
                new EmergenciaBicicletaEvent.Triage(true, true, false, false, EmergencyPriority.P3));
        assertThat(event.viaje()).isEqualTo(new EmergenciaBicicletaEvent.Viaje(
                7L, NOW.minusSeconds(600).toEpochMilli(), 10L, "11 de Septiembre", 5L, "BIKE-03520"));
        assertThat(event.usuario()).isEqualTo(
                new EmergenciaBicicletaEvent.Usuario(1L, "Ana", "Pérez", "ana@example.com"));
    }

    @Test
    void elCuestionarioNoSeAceptaMientrasSePuedeCancelarNiDosVeces() {
        BikeEmergency emergency = givenPending();
        EmergencyTriageRequest answers = new EmergencyTriageRequest(true, false, false);

        assertThatThrownBy(() -> service(NOW.plusSeconds(5)).submitTriage(1L, 91L, answers))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("todavía se puede cancelar");

        emergency.setStatus(EmergencyStatus.REPORTED);
        assertThatThrownBy(() -> service(NOW.plusSeconds(15)).submitTriage(1L, 91L, answers))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void alVencerElPlazoSinCuestionarioSeAvisaConLaPeorPrioridad() {
        BikeEmergency emergency = givenPending();

        assertThat(service(NOW.plusSeconds(69)).sendIfTriageExpired(91L)).isFalse();
        verifyNoInteractions(eventPublisher);

        assertThat(service(NOW.plusSeconds(70)).sendIfTriageExpired(91L)).isTrue();
        assertThat(emergency.getStatus()).isEqualTo(EmergencyStatus.REPORTED);
        assertThat(emergency.getPriority()).isEqualTo(EmergencyPriority.P1);
        assertThat(publishedEvent().triage())
                .isEqualTo(new EmergenciaBicicletaEvent.Triage(false, null, null, null, EmergencyPriority.P1));

        // Ya enviada, otra pasada del job no la reenvía.
        assertThat(service(NOW.plusSeconds(80)).sendIfTriageExpired(91L)).isFalse();
        assertThat(service(NOW.plusSeconds(80)).sendIfTriageExpired(99L)).isFalse();
    }

    @Test
    void laEmergenciaEnCursoEsLaDelViajePausado() {
        BikeEmergency emergency = pending();
        when(userRepository.existsById(1L)).thenReturn(true);
        when(emergencyRepository.findFirstByUserIdAndStatusInOrderByReportedAtDescIdDesc(eq(1L), anyCollection()))
                .thenReturn(Optional.of(emergency));

        trip.setStatus(TripStatus.EMERGENCY);
        assertThat(service(NOW).findCurrent(1L)).hasValueSatisfying(response ->
                assertThat(response.id()).isEqualTo(91L));

        // Cerrado el viaje (por ejemplo, cuando Emergencias lo resuelva), deja de estar en curso.
        trip.setStatus(TripStatus.COMPLETED);
        assertThat(service(NOW).findCurrent(1L)).isEmpty();

        when(userRepository.existsById(2L)).thenReturn(false);
        assertThatThrownBy(() -> service(NOW).findCurrent(2L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private EmergencyService service(Instant now) {
        return new EmergencyService(emergencyRepository, tripRepository, userRepository, bikeService,
                eventPublisher, WINDOWS, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void givenUserAndTrip() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
    }

    private BikeEmergency givenPending() {
        BikeEmergency emergency = pending();
        when(emergencyRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(emergency));
        return emergency;
    }

    private BikeEmergency pending() {
        return BikeEmergency.builder()
                .id(91L).trip(trip).user(user).bike(bike)
                .status(EmergencyStatus.PENDING)
                .latitude(new BigDecimal("-34.603700")).longitude(new BigDecimal("-58.381600"))
                .accuracyMeters(new BigDecimal("12.50"))
                .reportedAt(NOW)
                .cancelableUntil(NOW.plusSeconds(10))
                .triageDeadline(NOW.plusSeconds(70))
                .build();
    }

    private EmergenciaBicicletaEvent publishedEvent() {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq("EmergenciaBicicleta"), eq(AggregateType.TRIP), eq(7L), data.capture());
        return (EmergenciaBicicletaEvent) data.getValue();
    }
}
