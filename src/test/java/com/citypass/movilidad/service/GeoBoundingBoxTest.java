package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoBoundingBoxTest {

    private static final double OBELISCO_LAT = -34.6037;
    private static final double OBELISCO_LNG = -58.3816;

    @Test
    void laCajaContieneAlPuntoConsultado() {
        GeoBoundingBox box = GeoBoundingBox.around(OBELISCO_LAT, OBELISCO_LNG, 500);

        assertThat(box.minLatitude()).isLessThan(OBELISCO_LAT);
        assertThat(box.maxLatitude()).isGreaterThan(OBELISCO_LAT);
        assertThat(box.minLongitude()).isLessThan(OBELISCO_LNG);
        assertThat(box.maxLongitude()).isGreaterThan(OBELISCO_LNG);
    }

    @Test
    void laCajaCreceConElRadio() {
        GeoBoundingBox chica = GeoBoundingBox.around(OBELISCO_LAT, OBELISCO_LNG, 500);
        GeoBoundingBox grande = GeoBoundingBox.around(OBELISCO_LAT, OBELISCO_LNG, 5000);

        assertThat(grande.maxLatitude() - grande.minLatitude())
                .isGreaterThan(chica.maxLatitude() - chica.minLatitude());
        assertThat(grande.maxLongitude() - grande.minLongitude())
                .isGreaterThan(chica.maxLongitude() - chica.minLongitude());
    }

    @Test
    void medioGradoDeLatitudSonUnos500MetrosPorCadaKilometroDeRadio() {
        GeoBoundingBox box = GeoBoundingBox.around(0, 0, 1000);

        // 1 km sobre un meridiano son ~0.008993 grados de latitud.
        double latitudeDelta = box.maxLatitude() - box.minLatitude();
        assertThat(latitudeDelta).isCloseTo(0.017986, org.assertj.core.data.Offset.offset(0.0005));
    }

    @Test
    void aMayorLatitudLaCajaSeEnsanchaEnLongitud() {
        GeoBoundingBox enElEcuador = GeoBoundingBox.around(0, 0, 1000);
        GeoBoundingBox enLatitudAlta = GeoBoundingBox.around(60, 0, 1000);

        assertThat(enLatitudAlta.maxLongitude() - enLatitudAlta.minLongitude())
                .isGreaterThan(enElEcuador.maxLongitude() - enElEcuador.minLongitude());
    }

    @Test
    void cercaDelAntimeridianoAbreElRangoCompletoDeLongitud() {
        GeoBoundingBox box = GeoBoundingBox.around(0, 179.99, 5000);

        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    void cruzandoElAntimeridianoPorElLadoNegativoTambienAbreElRangoCompleto() {
        GeoBoundingBox box = GeoBoundingBox.around(0, -179.99, 5000);

        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    void cercaDelPoloSurTambienAbreElRangoCompletoDeLongitud() {
        GeoBoundingBox box = GeoBoundingBox.around(-89.999, 0, 5000);

        assertThat(box.minLatitude()).isEqualTo(-90.0);
        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    void cercaDelPoloAbreElRangoCompletoDeLongitudYRecortaLaLatitud() {
        GeoBoundingBox box = GeoBoundingBox.around(89.999, 0, 5000);

        assertThat(box.maxLatitude()).isEqualTo(90.0);
        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }
}
