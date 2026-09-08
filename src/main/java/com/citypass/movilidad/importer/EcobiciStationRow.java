package com.citypass.movilidad.importer;

public record EcobiciStationRow(
        String externalId,
        String name,
        String address,
        String latitude,
        String longitude,
        String capacity
) {
}
