VALID = {
    "purpose": "PICKUP",
    "user": {"lat": -34.6037, "lng": -58.3816},
    "stations": [
        {"stationId": 1, "lat": -34.6028, "lng": -58.3816, "capacity": 20, "availableBikes": 0, "availableDocks": 20},
        {"stationId": 2, "lat": -34.6001, "lng": -58.3816, "capacity": 20, "availableBikes": 9, "availableDocks": 11},
    ],
}


def test_health_informa_el_modelo_vigente(client, bundle):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "modelVersion": bundle.version}


def test_model_card_documenta_algoritmo_features_y_metricas(client):
    card = client.get("/v1/model").json()

    assert "LogisticRegression" in card["algorithm"]
    assert len(card["features"]) == 5
    assert card["purposes"]["PICKUP"]["holdoutMetrics"]["roc_auc"] > 0.8
    assert card["limitations"]


def test_recomendacion_en_camel_case(client):
    response = client.post("/v1/recommendations", json=VALID)

    body = response.json()
    assert response.status_code == 200
    assert body["status"] == "RECOMMENDED"
    assert body["recommendation"]["stationId"] == 2
    assert body["explanation"]["code"] == "NEAREST_HAS_NO_BIKES"
    assert body["explanation"]["comparedTo"]["stationId"] == 1
    assert {"stationId", "score", "rank", "distanceMeters"} <= body["ranking"][0].keys()


def test_sin_candidatas_responde_200_controlado(client):
    response = client.post("/v1/recommendations", json={**VALID, "stations": []})

    assert response.status_code == 200
    assert response.json()["reason"] == "NO_CANDIDATES"


def test_purpose_por_defecto_es_pickup(client):
    payload = {k: v for k, v in VALID.items() if k != "purpose"}

    assert client.post("/v1/recommendations", json=payload).json()["purpose"] == "PICKUP"


def test_rechaza_coordenadas_fuera_de_rango(client):
    response = client.post("/v1/recommendations", json={**VALID, "user": {"lat": 95, "lng": -58.38}})

    assert response.status_code == 422


def test_rechaza_ids_repetidos_y_stock_negativo(client):
    duplicated = {**VALID, "stations": [VALID["stations"][0], VALID["stations"][0]]}
    negative = {**VALID, "stations": [{**VALID["stations"][0], "availableBikes": -1}]}

    assert client.post("/v1/recommendations", json=duplicated).status_code == 422
    assert client.post("/v1/recommendations", json=negative).status_code == 422


def test_rechaza_purpose_desconocido(client):
    assert client.post("/v1/recommendations", json={**VALID, "purpose": "PARKING"}).status_code == 422
