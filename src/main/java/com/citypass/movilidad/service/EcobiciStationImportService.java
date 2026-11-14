package com.citypass.movilidad.service;

import com.citypass.movilidad.importer.EcobiciStationRow;
import com.citypass.movilidad.importer.StationImportResult;
import com.citypass.movilidad.mapper.EcobiciStationMapper;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.NeighborhoodRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Importa las estaciones del dataset oficial de Ecobici. Idempotente: saltea las que ya existen. */
@Service
public class EcobiciStationImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EcobiciStationImportService.class);
    private final StationRepository stationRepository;
    private final EcobiciStationMapper stationMapper;
    private final ObjectMapper objectMapper;
    private final NeighborhoodRepository neighborhoods;

    public EcobiciStationImportService(
            StationRepository stationRepository,
            EcobiciStationMapper stationMapper,
            ObjectMapper objectMapper,
            NeighborhoodRepository neighborhoods
    ) {
        this.stationRepository = stationRepository;
        this.stationMapper = stationMapper;
        this.objectMapper = objectMapper;
        this.neighborhoods = neighborhoods;
    }

    public StationImportResult importStations(Resource dataset) throws IOException {
        int processed = 0;
        int imported = 0;
        int duplicates = 0;
        int invalid = 0;
        // Una sola consulta de las ya importadas: corre en cada arranque y fila por fila sería lento.
        Set<String> seenExternalIds = new HashSet<>(stationRepository.findAllExternalIds());

        JsonNode features;
        try (var inputStream = dataset.getInputStream()) {
            features = objectMapper.readTree(inputStream).path("features");
        }
        if (!features.isArray()) {
            throw new IOException("Ecobici GeoJSON does not contain a features array");
        }

        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            try {
                processed++;
                EcobiciStationRow row = mapFeature(properties);
                String externalId = row.externalId().trim();
                if (!seenExternalIds.add(externalId)) {
                    duplicates++;
                    continue;
                }
                var station = stationMapper.toStation(row);
                neighborhoods.findByNormalizedName(NeighborhoodNames.normalize(row.neighborhood()))
                        .ifPresent(station::setNeighborhood);
                stationRepository.saveAndFlush(station);
                imported++;
            } catch (IllegalArgumentException | DataIntegrityViolationException exception) {
                invalid++;
                LOGGER.warn("Skipping invalid Ecobici feature {}: {}", processed, exception.getMessage());
            }
        }
        return new StationImportResult(processed, imported, duplicates, invalid);
    }

    private EcobiciStationRow mapFeature(JsonNode properties) {
        String externalId = "ecobici-" + text(properties, "ID") + "-" + text(properties, "NUMERO");
        return new EcobiciStationRow(
                externalId,
                text(properties, "NOMBRE"),
                text(properties, "DIRECCION"),
                text(properties, "Lat"),
                text(properties, "Lon"),
                text(properties, "ANCLAJES"),
                text(properties, "BARRIO")
        );
    }

    private String text(JsonNode properties, String field) {
        JsonNode value = properties.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
