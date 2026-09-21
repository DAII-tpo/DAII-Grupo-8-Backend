package com.citypass.movilidad.mapper;

import com.citypass.movilidad.importer.EcobiciStationRow;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EcobiciStationMapper {

    private static final BigDecimal MIN_CABA_LATITUDE = new BigDecimal("-35");
    private static final BigDecimal MAX_CABA_LATITUDE = new BigDecimal("-34");
    private static final BigDecimal MIN_CABA_LONGITUDE = new BigDecimal("-59");
    private static final BigDecimal MAX_CABA_LONGITUDE = new BigDecimal("-58");

    public Station toStation(EcobiciStationRow row) {
        String externalId = required(row.externalId(), "externalId", 64);
        String name = required(row.name(), "name", 150);
        String address = optional(row.address(), 255);
        BigDecimal latitude = decimal(row.latitude(), "latitude");
        BigDecimal longitude = decimal(row.longitude(), "longitude");
        int capacity = integer(row.capacity(), "capacity");

        if (latitude.compareTo(MIN_CABA_LATITUDE) < 0 || latitude.compareTo(MAX_CABA_LATITUDE) > 0
                || longitude.compareTo(MIN_CABA_LONGITUDE) < 0 || longitude.compareTo(MAX_CABA_LONGITUDE) > 0) {
            throw new IllegalArgumentException("coordinates are outside CABA");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity cannot be negative");
        }

        Station station = new Station();
        station.setExternalId(externalId);
        station.setName(name);
        station.setAddress(address);
        station.setLatitude(latitude);
        station.setLongitude(longitude);
        station.setCapacity(capacity);
        station.setStatus(StationStatus.ACTIVE);
        station.setSource(StationSource.DATASET);
        return station;
    }

    private String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("address exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(required(value, field, 50));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is not a decimal number", exception);
        }
    }

    private int integer(String value, String field) {
        try {
            return Integer.parseInt(required(value, field, 10));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is not an integer", exception);
        }
    }
}
