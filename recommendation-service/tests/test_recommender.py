from app.features import FEATURE_NAMES, Purpose
from app.recommender import recommend
from app.schemas import ExplanationCode, NoRecommendationReason, RecommendationStatus
from tests.conftest import ConstantScorer, request, station


def test_recomienda_la_mas_cercana_cuando_tiene_buena_disponibilidad(scorer):
    result = recommend(request(station(1, 110, bikes=10, docks=10), station(2, 800, bikes=10, docks=10)), scorer)

    assert result.status == RecommendationStatus.RECOMMENDED
    assert result.recommendation.station_id == 1
    assert result.model_version == scorer.version
    assert result.explanation.code == ExplanationCode.NEAREST_IS_BEST
    assert result.explanation.compared_to is None


def test_una_mas_lejana_con_stock_le_gana_a_una_cercana_vacia(scorer):
    result = recommend(request(station(1, 100, bikes=0, docks=20), station(2, 400, bikes=9, docks=11)), scorer)

    assert result.recommendation.station_id == 2
    assert result.explanation.code == ExplanationCode.NEAREST_HAS_NO_BIKES
    assert result.explanation.compared_to.station_id == 1
    assert result.explanation.compared_to.available_bikes == 0


def test_el_modelo_prefiere_stock_seguro_sobre_una_sola_bici_cercana(scorer):
    result = recommend(request(station(1, 120, bikes=1, docks=19), station(2, 400, bikes=12, docks=8)), scorer)

    assert result.recommendation.station_id == 2
    assert result.explanation.code == ExplanationCode.NEAREST_LOW_AVAILABILITY
    assert result.recommendation.score > result.ranking[1].score


def test_no_manda_lejos_por_una_mejora_chica_de_stock(scorer):
    result = recommend(request(station(1, 100, bikes=8, docks=12), station(2, 1400, bikes=15, docks=5)), scorer)

    assert result.recommendation.station_id == 1


def test_en_devolucion_usa_anclajes_libres_y_no_bicicletas(scorer):
    stations = (station(1, 100, bikes=20, docks=0), station(2, 350, bikes=0, docks=12))

    dropoff = recommend(request(*stations, purpose=Purpose.DROPOFF), scorer)
    pickup = recommend(request(*stations, purpose=Purpose.PICKUP), scorer)

    assert dropoff.recommendation.station_id == 2
    assert dropoff.explanation.code == ExplanationCode.NEAREST_NO_DOCKS
    assert pickup.recommendation.station_id == 1


def test_empate_de_score_se_desempata_por_mas_disponibilidad():
    result = recommend(request(station(1, 100, bikes=3, docks=17), station(2, 300, bikes=9, docks=11)),
                       ConstantScorer())

    assert result.recommendation.station_id == 2


def test_empate_de_score_y_stock_se_desempata_por_cercania_y_despues_por_id():
    by_distance = recommend(request(station(1, 300, bikes=5, docks=5), station(2, 100, bikes=5, docks=5)),
                            ConstantScorer())
    by_id = recommend(request(station(7, 100, bikes=5, docks=5), station(3, 100, bikes=5, docks=5)),
                      ConstantScorer())

    assert by_distance.recommendation.station_id == 2
    assert by_id.recommendation.station_id == 3


def test_sin_candidatas_devuelve_fallback_controlado(scorer):
    result = recommend(request(), scorer)

    assert result.status == RecommendationStatus.NO_RECOMMENDATION
    assert result.reason == NoRecommendationReason.NO_CANDIDATES
    assert result.recommendation is None
    assert result.ranking == []


def test_sin_estaciones_viables_devuelve_fallback_con_ranking(scorer):
    result = recommend(request(station(1, 100, bikes=0, docks=20), station(2, 200, bikes=0, docks=20)), scorer)

    assert result.status == RecommendationStatus.NO_RECOMMENDATION
    assert result.reason == NoRecommendationReason.NO_VIABLE_STATION
    assert result.recommendation is None
    assert len(result.ranking) == 2


def test_ranking_completo_y_hasta_dos_alternativas_viables(scorer):
    stations = [station(i, 100 * i, bikes=0 if i == 2 else 6, docks=10) for i in range(1, 6)]

    result = recommend(request(*stations), scorer)

    assert [r.rank for r in result.ranking] == [1, 2, 3, 4, 5]
    assert result.ranking[-1].station_id == 2          # la estación vacía queda última
    assert len(result.alternatives) == 2
    assert all(a.station_id != 2 for a in result.alternatives)
    assert result.recommendation.station_id not in [a.station_id for a in result.alternatives]


def test_los_factores_vienen_ordenados_por_impacto(scorer):
    result = recommend(request(station(1, 100, bikes=0, docks=20), station(2, 400, bikes=9, docks=11)), scorer)

    impacts = [abs(f.impact) for f in result.explanation.factors]
    assert impacts == sorted(impacts, reverse=True)
    assert len(result.explanation.factors) == len(FEATURE_NAMES)
