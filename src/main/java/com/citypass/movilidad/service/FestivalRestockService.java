package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class FestivalRestockService {
    private static final int MIN_CAPACITY = 1000;
    private static final int RADIUS_METERS = 300;

    private final NearbyStationService stations;
    private final FestivalRestockTaskRepository tasks;
    private final Clock clock;

    public FestivalRestockService(NearbyStationService stations, FestivalRestockTaskRepository tasks, Clock clock) {
        this.stations = stations;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public void planFromActivity(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new InvalidEventException("ActividadCreada: data inválido");
        }
        String state = requiredText(data, "estado", 32);
        long capacity = requiredInteger(data, "capacidad");
        if (!"PROGRAMADA".equals(state) || capacity < MIN_CAPACITY) {
            return;
        }

        String activityId = requiredText(data, "actividadId", 128);
        String title = requiredText(data, "titulo", 255);
        long start = requiredInteger(data, "fechaHoraInicio");
        long end = requiredInteger(data, "fechaHoraFinalizacion");
        if (end <= start) {
            throw new InvalidEventException("ActividadCreada: la finalización debe ser posterior al inicio");
        }
        if (end <= clock.millis()) {
            return;
        }
        JsonNode correlation = data.path("correlationId");
        String correlationId = correlation.isMissingNode() || correlation.isNull()
                ? "act-" + activityId : requiredText(data, "correlationId", 132);

        JsonNode location = data.path("ubicacion");
        double lat = requiredCoordinate(location, "lat", 90);
        double lng = requiredCoordinate(location, "lng", 180);

        for (var station : stations.findAllWithin(lat, lng, RADIUS_METERS)) {
            tasks.createIfAbsent(activityId, correlationId, title, end, station.stationId(), station.distanceMeters());
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<FestivalRestockTaskResponse> findUpcoming() {
        return tasks.findUpcoming(clock.instant());
    }

    private static String requiredText(JsonNode data, String field, int maxLength) {
        JsonNode value = data.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength) {
            throw new InvalidEventException("ActividadCreada: " + field + " inválido");
        }
        return value.textValue();
    }

    private static long requiredInteger(JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidEventException("ActividadCreada: " + field + " debe ser un entero");
        }
        return value.longValue();
    }

    private static double requiredCoordinate(JsonNode data, String field, double bound) {
        JsonNode value = data.path(field);
        double coordinate = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(coordinate) || Math.abs(coordinate) > bound) {
            throw new InvalidEventException("ActividadCreada: ubicación." + field + " inválida");
        }
        return coordinate;
    }
}
