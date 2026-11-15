package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.event.FestivalActivityCreatedHandler;
import com.citypass.movilidad.eventbus.consume.IncomingEvent;
import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository.Activity;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FestivalRestockServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-14T18:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(600);
    private static final BigDecimal LATITUDE = new BigDecimal("-34.6");
    private static final BigDecimal LONGITUDE = new BigDecimal("-58.4");
    private final NearbyStationService stations = mock(NearbyStationService.class);
    private final FestivalRestockTaskRepository tasks = mock(FestivalRestockTaskRepository.class);
    private final StationRepository stationRepository = mock(StationRepository.class);
    private final BikeRepository bikes = mock(BikeRepository.class);
    private final FestivalRestockService service = new FestivalRestockService(
            stations, tasks, Clock.fixed(NOW, ZoneOffset.UTC), stationRepository, bikes,
            mock(RestockDonorRanking.class), mock(PlatformTransactionManager.class));

    @Test
    void assignsTasksFromTheBusAndKeepsTheProvidedCorrelationId() {
        var data = activity();
        when(stations.findAllWithin(-34.6, -58.4, 300)).thenReturn(List.of(nearby(1L, 45, 8)));
        var handler = new FestivalActivityCreatedHandler(service);

        assertThat(handler.topic()).isEqualTo("com.citypass.espacios.ActividadCreada.v2");
        handler.handle(new IncomingEvent(handler.topic(), null, data));

        verify(tasks).createIfAbsent(new Activity("festival", "ensayo", "Festival",
                        NOW.plusSeconds(60).toEpochMilli(), DEADLINE.toEpochMilli(), 300),
                1L, 45, 9, null);
    }

    @Test
    void ignoresSuspendedActivities() {
        service.planFromActivity(activity().put("estado", "SUSPENDIDA"));
        verifyNoInteractions(stations, tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"estado", "titulo", "actividadId", "capacidad", "fechaHoraInicio", "fechaHoraFinalizacion"})
    void rejectsIncompleteActivities(String field) {
        var incomplete = activity();
        incomplete.remove(field);
        assertThatThrownBy(() -> service.planFromActivity(incomplete)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(stations, tasks);
    }

    @Test
    void rejectsBackwardSchedulesAndCoordinatesOutsideTheMap() {
        var backward = activity().put("fechaHoraFinalizacion", NOW.toEpochMilli());
        assertThatThrownBy(() -> service.planFromActivity(backward)).isInstanceOf(InvalidEventException.class);
        var invalidLocation = activity();
        invalidLocation.putObject("ubicacion").put("lat", -34.6).put("lng", 181);
        assertThatThrownBy(() -> service.planFromActivity(invalidLocation)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.planFromActivity(null)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(stations, tasks);
    }

    @Test
    void choosesTheClosestDonorAmongEqualOffersAndExcludesProtectedStations() {
        when(tasks.find(7L, NOW.toEpochMilli())).thenReturn(Optional.of(pending(4, 6)));
        when(stationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(
                Station.builder().latitude(LATITUDE).longitude(LONGITUDE).build()));
        when(tasks.findPending(NOW.toEpochMilli())).thenReturn(List.of(pending(4, 6)));
        when(tasks.highestUpcomingTargets(NOW.toEpochMilli())).thenReturn(Map.of(2L, 10));
        var donorStocks = List.of(donorStock(4L), donorStock(5L));
        when(bikes.countBikesByStationIds(Set.of(4L, 5L), BikeStatus.AVAILABLE))
                .thenReturn(donorStocks);
        when(stations.findAllWithin(-34.6, -58.4, 2000)).thenReturn(List.of(
                nearby(1L, 0, 10), nearby(2L, 10, 10), nearby(3L, 20, 5),
                nearby(4L, 200, 9), nearby(5L, 100, 9)));

        assertThat(service.suggestTransfer(7L))
                .containsExactly(new FestivalRestockService.TransferSuggestion(5L, "Origen 5", 4, 100));
    }

    @Test
    void skipsTransfersWithoutFreeSlotsOrAnActiveDestination() {
        when(tasks.find(7L, NOW.toEpochMilli())).thenReturn(Optional.of(pending(4, 0)), Optional.of(pending(4, 6)));

        when(tasks.findPending(NOW.toEpochMilli())).thenReturn(List.of(pending(4, 0)), List.of(pending(4, 6)));

        assertThat(service.suggestTransfer(7L)).isEmpty();
        assertThat(service.suggestTransfer(7L)).isEmpty();
        verifyNoInteractions(stations);
    }

    private static StationBikeCountProjection donorStock(long id) {
        var stock = mock(StationBikeCountProjection.class);
        when(stock.getStationId()).thenReturn(id);
        when(stock.getAvailableBikes()).thenReturn(9L);
        when(stock.getTotalBikes()).thenReturn(9L);
        return stock;
    }

    private static FestivalRestockTaskResponse pending(int available, int slots) {
        return new FestivalRestockTaskResponse(7L, "festival", "ensayo", "Festival", 1L, "Destino",
                10, 300, DEADLINE, 10, available, available, slots, 9, "PENDING", null, null,
                null, null, null, null, false);
    }

    private static NearbyStationResponse nearby(Long id, int distance, int available) {
        return new NearbyStationResponse(id, "Origen " + id, null, LATITUDE, LONGITUDE,
                distance, 10, available, 10 - available);
    }

    private static ObjectNode activity() {
        var data = new ObjectMapper().createObjectNode()
                .put("actividadId", "festival").put("correlationId", "ensayo").put("titulo", "Festival")
                .put("estado", "PROGRAMADA").put("capacidad", 1000)
                .put("fechaHoraInicio", NOW.plusSeconds(60).toEpochMilli())
                .put("fechaHoraFinalizacion", DEADLINE.toEpochMilli());
        data.putObject("ubicacion").put("lat", -34.6).put("lng", -58.4);
        return data;
    }
}
