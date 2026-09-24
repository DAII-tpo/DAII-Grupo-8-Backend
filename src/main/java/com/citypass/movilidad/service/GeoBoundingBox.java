package com.citypass.movilidad.service;

/**
 * Caja de coordenadas que contiene al círculo de búsqueda. Es un prefiltro que aprovecha el índice
 * (lat, lng); el filtro exacto por distancia lo hace la consulta.
 */
public record GeoBoundingBox(double minLatitude, double maxLatitude,
                             double minLongitude, double maxLongitude) {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final double MIN_LATITUDE = -90.0;
    private static final double MAX_LATITUDE = 90.0;
    private static final double MIN_LONGITUDE = -180.0;
    private static final double MAX_LONGITUDE = 180.0;

    public static GeoBoundingBox around(double latitude, double longitude, int radiusMeters) {
        double latitudeDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS);
        double minLatitude = latitude - latitudeDelta;
        double maxLatitude = latitude + latitudeDelta;

        // Cerca de los polos el círculo abarca todas las longitudes.
        if (minLatitude <= MIN_LATITUDE || maxLatitude >= MAX_LATITUDE) {
            return fullLongitudeRange(minLatitude, maxLatitude);
        }

        // El grado de longitud se acorta con la latitud: se usa el borde más cercano al polo.
        double worstCaseLatitude = Math.max(Math.abs(minLatitude), Math.abs(maxLatitude));
        double cosine = Math.cos(Math.toRadians(worstCaseLatitude));
        double longitudeDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_METERS * cosine));
        double minLongitude = longitude - longitudeDelta;
        double maxLongitude = longitude + longitudeDelta;

        // Si cruza el antimeridiano se abre todo el rango de longitudes.
        if (minLongitude < MIN_LONGITUDE || maxLongitude > MAX_LONGITUDE) {
            return fullLongitudeRange(minLatitude, maxLatitude);
        }
        return new GeoBoundingBox(minLatitude, maxLatitude, minLongitude, maxLongitude);
    }

    private static GeoBoundingBox fullLongitudeRange(double minLatitude, double maxLatitude) {
        return new GeoBoundingBox(
                Math.max(minLatitude, MIN_LATITUDE),
                Math.min(maxLatitude, MAX_LATITUDE),
                MIN_LONGITUDE,
                MAX_LONGITUDE);
    }
}
