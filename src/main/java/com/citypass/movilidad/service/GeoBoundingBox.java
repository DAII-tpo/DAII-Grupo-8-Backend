package com.citypass.movilidad.service;

/**
 * Caja de coordenadas que contiene por completo al círculo de radio dado alrededor de un
 * punto. Sirve como prefiltro barato de la búsqueda de estaciones cercanas: permite que la
 * consulta use el índice (latitude, longitude) creado en MOV-012 antes de calcular la
 * distancia real, que es exacta pero no puede aprovechar el índice.
 *
 * Siempre devuelve un superconjunto del círculo: puede incluir estaciones de más, nunca de
 * menos. El filtro definitivo por distancia lo aplica la consulta.
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

        // El grado de longitud se acorta con la latitud: uso el borde más cercano al polo
        // para que la caja siga conteniendo al círculo entero. Si el coseno fuera diminuto,
        // el delta se dispara (o da Infinity) y el control de ±180 de abajo abre el rango
        // completo, que es justamente lo correcto cerca de los polos.
        double worstCaseLatitude = Math.max(Math.abs(minLatitude), Math.abs(maxLatitude));
        double cosine = Math.cos(Math.toRadians(worstCaseLatitude));
        double longitudeDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_METERS * cosine));
        double minLongitude = longitude - longitudeDelta;
        double maxLongitude = longitude + longitudeDelta;

        // Si la caja cruzaría el antimeridiano, un BETWEEN dejaría afuera la mitad que da la
        // vuelta. Abrir el rango completo es correcto (sigue siendo superconjunto) y simple.
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
