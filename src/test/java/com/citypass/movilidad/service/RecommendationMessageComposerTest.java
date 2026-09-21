package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.StationRecommendationResponse.ComparedStation;
import com.citypass.movilidad.dto.StationRecommendationResponse.RecommendedStation;
import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mensaje es lo único de la respuesta que el usuario lee tal cual, así que se verifica
 * palabra por palabra: nombres reales de estación, plurales correctos y ninguna afirmación
 * sobre el historial de la estación, que el modelo no conoce.
 */
class RecommendationMessageComposerTest {

    private final RecommendationMessageComposer composer = new RecommendationMessageComposer();

    @Test
    void anunciaLaMasCercanaCuandoEsTambienLaMejor() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.PICKUP,
                ExplanationCode.NEAREST_IS_BEST, station("CERRITO", 236, 13, 17), null);

        assertThat(mensaje).isEqualTo("CERRITO es la estación más cercana (236 m) y tiene 13 bicicletas "
                + "disponibles.");
    }

    @Test
    void explicaQueLaMasCercanaSeQuedoSinBicicletas() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.PICKUP,
                ExplanationCode.NEAREST_HAS_NO_BIKES, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 0, 18));

        assertThat(mensaje).isEqualTo("Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): "
                + "DIAGONAL NORTE no tiene bicicletas disponibles; en CERRITO hay 13.");
    }

    @Test
    void avisaQueALaMasCercanaLeQuedaUnaSolaBicicleta() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.PICKUP,
                ExplanationCode.NEAREST_LOW_AVAILABILITY, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 1, 17));

        assertThat(mensaje).isEqualTo("Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): "
                + "en DIAGONAL NORTE queda solo 1 bicicleta disponible y podría no estar cuando llegues; "
                + "en CERRITO hay 13.");
    }

    @Test
    void usaElPluralCuandoALaMasCercanaLeQuedanDos() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.PICKUP,
                ExplanationCode.NEAREST_LOW_AVAILABILITY, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 2, 16));

        assertThat(mensaje).contains("quedan solo 2 bicicletas disponibles y podrían no estar cuando llegues");
    }

    @Test
    void hablaDeAnclajesCuandoElUsuarioVaADevolver() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.DROPOFF,
                ExplanationCode.NEAREST_FEW_DOCKS, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 17, 1));

        assertThat(mensaje).isEqualTo("Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): "
                + "en DIAGONAL NORTE queda solo 1 anclaje libre y podría ocuparse antes de que llegues; "
                + "en CERRITO hay 17.");
    }

    @Test
    void explicaQueLaMasCercanaNoTieneAnclajes() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.DROPOFF,
                ExplanationCode.NEAREST_NO_DOCKS, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 18, 0));

        assertThat(mensaje).contains("DIAGONAL NORTE no tiene anclajes libres; en CERRITO hay 17.");
    }

    @Test
    void comparaLaDisponibilidadContraLosMetrosExtra() {
        String mensaje = composer.forRecommendation(RecommendationPurpose.PICKUP,
                ExplanationCode.BETTER_AVAILABILITY_NEARBY, station("CERRITO", 236, 13, 17),
                compared("DIAGONAL NORTE", 193, 5, 13));

        assertThat(mensaje).isEqualTo("Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): "
                + "en CERRITO hay 13 bicicletas disponibles contra 5 en DIAGONAL NORTE, caminando 43 m más.");
    }

    @Test
    void describeElCriterioDeRespaldoSinMencionarLaFalla() {
        String mensaje = composer.forFallback(RecommendationPurpose.PICKUP, station("CERRITO", 236, 13, 17));

        assertThat(mensaje).isEqualTo("Te recomendamos CERRITO (236 m), la estación más cercana con "
                + "bicicletas disponibles: hay 13.");
        assertThat(mensaje).doesNotContainIgnoringCase("error");
    }

    @Test
    void explicaQueNoHayEstacionesCerca() {
        String mensaje = composer.forNoRecommendation(RecommendationPurpose.PICKUP,
                NoRecommendationReason.NO_CANDIDATES);

        assertThat(mensaje).isEqualTo("No encontramos estaciones cerca de tu ubicación.");
    }

    @Test
    void explicaQueNingunaEstacionTieneElRecursoNecesario() {
        assertThat(composer.forNoRecommendation(RecommendationPurpose.PICKUP,
                NoRecommendationReason.NO_VIABLE_STATION))
                .isEqualTo("Ninguna estación cerca tiene bicicletas disponibles en este momento.");
        assertThat(composer.forNoRecommendation(RecommendationPurpose.DROPOFF,
                NoRecommendationReason.NO_VIABLE_STATION))
                .isEqualTo("Ninguna estación cerca tiene anclajes libres en este momento.");
    }

    private RecommendedStation station(String name, int distance, int bikes, int slots) {
        return new RecommendedStation(1L, name, "Dirección de " + name, new BigDecimal("-34.6026"),
                new BigDecimal("-58.3838"), distance, 30, bikes, slots, 0.76);
    }

    private ComparedStation compared(String name, int distance, int bikes, int slots) {
        return new ComparedStation(2L, name, distance, bikes, slots);
    }
}
