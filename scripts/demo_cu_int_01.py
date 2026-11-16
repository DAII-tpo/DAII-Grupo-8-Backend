#!/usr/bin/env python3
"""Prepara inventario real de prueba mediante las APIs normales de Movilidad.

Python 3.10+, sin dependencias. Por defecto solo muestra un plan; --apply lo ejecuta.
Usar un token de personal ADMIN en MOVILIDAD_ADMIN_TOKEN (o ingresarlo sin eco).
No modifica estaciones, contratos, recomendaciones ni el gateway. Solo con
--trasladar-uade puede mover temporalmente las bicis AVAILABLE originales de UADE.

Ejemplos (desde la raíz del backend):
  python scripts/demo_cu_int_01.py preparar
  python scripts/demo_cu_int_01.py preparar --apply
  python scripts/demo_cu_int_01.py preparar --trasladar-uade
  python scripts/demo_cu_int_01.py preparar --trasladar-uade --apply
  python scripts/demo_cu_int_01.py sin-anclajes --apply
  python scripts/demo_cu_int_01.py sin-bicis --apply
  python scripts/demo_cu_int_01.py exito --apply
  python scripts/demo_cu_int_01.py reset --apply

reset da de baja SOLO las bicicletas creadas en esta sesión y devuelve las
originales a UADE, sin borrarlas ni cambiarles el estado. Los códigos no se
reutilizan porque el backend conserva las bajas lógicas. Conservar el archivo de
estado hasta completar reset. Las operaciones HTTP no forman una transacción
global: se guardan avances para poder recuperarlos si se corta la conexión.
El traslado busca una estación activa con lugar fuera del recorrido; se puede
elegir con --deposito-id ID_INTERNO. Los siguientes comandos usan la misma sesión
sin repetir --trasladar-uade. Las fechas de actualización y el historial normal
de la API se conservan: reset restaura el inventario, no borra la auditoría.

Validar devolución: --event-id ID (o MOVILIDAD_EVENT_ID). Para reproducir el mismo
origen de Espacios, indicar --origin-lat y --origin-lng; por defecto se consulta
desde las coordenadas de la estación UADE. Las consultas de recomendación no
publican EDA: coordinar una inscripción/baja de inscripción en Espacios.
"""

import argparse
from collections import Counter
from contextlib import contextmanager
import getpass
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


NAMES = {"uade": "UADE", "independencia": "INDEPENDENCIA", "planetario": "PLANETARIO",
         "urquiza": "GENERAL URQUIZA"}
SCENARIOS = ("exito", "sin-anclajes", "sin-bicis")
DEFAULT_URL = "https://movilidad-backend.onrender.com"
STATE_PATH = Path(__file__).resolve().parents[1] / ".demo-cu-int-01" / "state.json"
MAX_BIKES = 200


class DemoError(Exception):
    """Error operativo seguro para mostrar en la terminal."""


def require(condition, message):
    if not condition:
        raise DemoError(message)


def base_url(value):
    try:
        parts = urlsplit(value)
        _ = parts.port
    except ValueError:
        raise DemoError("URL del backend inválida.") from None
    require(parts.hostname and parts.username is None and parts.password is None
            and parts.path in ("", "/") and not parts.query and not parts.fragment,
            "Usá la URL raíz del backend, sin credenciales, query ni /api/v1.")
    require(parts.scheme == "https" or (parts.scheme == "http" and parts.hostname in
            ("localhost", "127.0.0.1", "::1")), "HTTP solo se admite en loopback local.")
    return value.rstrip("/")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise DemoError("El backend redirigió la petición. Revisá --base-url; no se reenvió el token.")


class Api:
    def __init__(self, url, token, timeout=15):
        self.url = base_url(url)
        self.token = token
        self.timeout = timeout
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, body=None):
        require(path.startswith("/api/v1/"), "Ruta de API inválida.")
        request = Request(self.url + path, method=method,
                          data=None if body is None else json.dumps(body).encode("utf-8"),
                          headers={"Authorization": "Bearer " + self.token,
                                   "Accept": "application/json", "Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
                require(len(raw) <= 4 * 1024 * 1024, "La respuesta de la API supera 4 MiB.")
                return json.loads(raw) if raw else None
        except HTTPError as error:
            # No imprimir cuerpos remotos, credenciales ni cabeceras.
            raise DemoError(f"API HTTP {error.code} en {method} {path.split('?')[0]}. "
                            "Verificá permisos, vigencia del token y disponibilidad del servicio.") from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise DemoError("No se obtuvo una respuesta JSON válida del backend. "
                            "Revisá conexión/URL y despertá Render antes de la presentación.") from None


def fingerprint(bike):
    return {key: bike.get(key) for key in ("id", "code", "stationId", "status", "updatedAt")}


def normalized(value):
    return "".join(c for c in unicodedata.normalize("NFKD", value.upper().strip())
                   if not unicodedata.combining(c))


def resolve_stations(stations, overrides):
    result = {}
    for alias, name in NAMES.items():
        matches = [s for s in stations if (s["id"] == overrides[alias] if alias in overrides
                   else normalized(s["name"]) == name)]
        require(len(matches) == 1, f"No se encontró una única estación {name}. "
                f"Usá --station {alias}=ID_INTERNO después de revisar el catálogo.")
        station = matches[0]
        require(normalized(station["name"]) == name, f"El ID indicado no corresponde a {name}.")
        require(station.get("status") == "ACTIVE" and not station.get("deletedAt"),
                f"{name} debe estar activa y sin baja.")
        require(isinstance(station.get("capacity"), int) and 0 < station["capacity"] <= MAX_BIKES,
                f"Capacidad inválida o excesiva en {name}.")
        require(all(isinstance(station.get(k), (int, float)) and math.isfinite(station[k])
                    and abs(station[k]) <= limit for k, limit in (("latitude", 90), ("longitude", 180))),
                f"{name} necesita coordenadas válidas.")
        result[alias] = {k: station[k] for k in
                         ("id", "name", "capacity", "status", "latitude", "longitude")}
    require(len({s["id"] for s in result.values()}) == 4, "Las cuatro estaciones deben ser distintas.")
    return result


class Store:
    def __init__(self, path):
        self.path = Path(path)

    def load(self):
        if not self.path.exists():
            return None
        try:
            state = json.loads(self.path.read_text(encoding="utf-8"))
            require(state["version"] == 1, "Versión de estado no soportada.")
            return state
        except (ValueError, KeyError):
            raise DemoError("Archivo de estado inválido. No lo borres si quedan bicicletas de prueba.") from None

    def save(self, state):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(dir=self.path.parent, prefix="state-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as out:
                json.dump(state, out, ensure_ascii=False, indent=2)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    @contextmanager
    def lock(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        lock = self.path.with_suffix(".lock")
        try:
            fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            raise DemoError("Hay otra ejecución o un bloqueo de una ejecución interrumpida. "
                            f"Revisá que no siga ejecutándose antes de retirar {lock}.") from None
        try:
            os.close(fd)
            yield
        finally:
            lock.unlink()


def read_inventory(api):
    stations = api.request("GET", "/api/v1/stations")
    bikes = api.request("GET", "/api/v1/bikes")
    require(isinstance(stations, list) and isinstance(bikes, list), "Formato de catálogo inesperado.")
    return stations, bikes


def foreign_inventory(state, bikes):
    codes = {b["code"] for b in state["bikes"]}
    codes.update(b["code"] for b in state.get("borrowed", []))
    ids = {s["id"] for s in state["stations"].values()}
    if state.get("parking"):
        ids.add(state["parking"]["id"])
    return sorted((fingerprint(b) for b in bikes if b["code"] not in codes and b.get("stationId") in ids),
                  key=lambda b: b["id"])


def station_snapshot(station):
    return {k: station.get(k) for k in ("id", "name", "capacity", "status", "latitude", "longitude")}


def distance(a, b):
    lat1, lat2 = math.radians(a["latitude"]), math.radians(b["latitude"])
    dlat = lat2 - lat1
    dlon = math.radians(b["longitude"] - a["longitude"])
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(value)))


def arrange_borrowing(state, stations, bikes, parking_id):
    originals = [b for b in bikes if b.get("stationId") == state["stations"]["uade"]["id"]
                 and b.get("status") == "AVAILABLE"]
    if not originals:
        return
    require(len(originals) <= MAX_BIKES, "Hay más de 200 bicicletas originales en UADE; revisar el catálogo.")
    occupied = Counter(b.get("stationId") for b in bikes)
    selected = {s["id"] for s in state["stations"].values()}
    candidates = []
    for station in stations:
        if parking_id is not None and station["id"] != parking_id:
            continue
        if station["id"] in selected or station.get("status") != "ACTIVE" or station.get("deletedAt"):
            continue
        if not isinstance(station.get("capacity"), int) or station["capacity"] - occupied[station["id"]] < len(originals):
            continue
        if not all(isinstance(station.get(k), (int, float)) and math.isfinite(station[k])
                   and abs(station[k]) <= limit for k, limit in (("latitude", 90), ("longitude", 180))):
            continue
        # Fuera del radio predeterminado de 1 km de ambas consultas de la demo.
        if min(distance(station, state["stations"][a]) for a in ("uade", "planetario")) < 1500:
            continue
        candidates.append(station)
    require(candidates, "No hay estación de depósito activa con lugar para las bicicletas originales, "
            "fuera de las cuatro estaciones y a más de 1500 m de UADE/PLANETARIO. "
            "Coordiná una estación y seleccionala con --deposito-id ID_INTERNO.")
    state["parking"] = station_snapshot(min(candidates, key=lambda s: s["id"]))
    state["borrowed"] = [{"code": b["code"], "model": b.get("model"), "original": fingerprint(b),
                          "expected": fingerprint(b), "returned": False} for b in originals]


def operation_record(state, operation):
    collection = state.get("borrowed", []) if operation.get("borrowed") else state["bikes"]
    return next(b for b in collection if b["code"] == operation["code"])


def matches_identity(state, record, actual, borrowed=False):
    if actual is None or actual.get("code") != record["code"]:
        return False
    if borrowed:
        return actual.get("id") == record["original"]["id"] and actual.get("model") == record["model"]
    return actual.get("model") == state["marker"] and (record["expected"] is None
            or actual.get("id") == record["expected"]["id"])


def recover_pending(state, bikes):
    """Reconcilia una respuesta perdida usando el código único y la operación registrada."""
    pending = state.get("pending")
    if not pending:
        return
    record = operation_record(state, pending)
    actual = next((b for b in bikes if b["code"] == record["code"]), None)
    if pending.get("borrowed"):
        require(pending["method"] == "PATCH" and matches_identity(state, record, actual, True)
                and actual["status"] == "AVAILABLE", "La bicicleta original cambió; revisar antes de restaurar.")
        if actual["stationId"] == pending["target"]:
            record["expected"] = fingerprint(actual)
            record["returned"] = pending["target"] == record["original"]["stationId"]
        else:
            require(fingerprint(actual) == record["expected"], "Cambió una bicicleta original fuera del script.")
        state["pending"] = None
        return
    if pending["method"] == "DELETE":
        if actual is None:
            record["deleted"] = True
        else:
            require(actual.get("model") == state["marker"] and fingerprint(actual) == record["expected"]
                    and actual["status"] == "AVAILABLE", "La bicicleta cambió durante el reset; no se elimina.")
    elif actual is not None and actual.get("model") == state["marker"]:
        require(actual["status"] == "AVAILABLE", "Una bicicleta de prueba está en uso o cambió de estado.")
        if pending["method"] == "POST" or actual["stationId"] == pending["target"]:
            record["expected"] = fingerprint(actual)
        else:
            require(fingerprint(actual) == record["expected"], "Se detectó un cambio ajeno en una bicicleta de prueba.")
    elif pending["method"] != "POST":
        raise DemoError("Falta una bicicleta de prueba; no se puede reconciliar la operación pendiente.")
    state["pending"] = None


def validate_inventory(state, stations, bikes, resetting=False):
    require(state["url"], "Estado sin backend asociado.")
    by_code = {b["code"]: b for b in bikes}
    for record in state["bikes"]:
        actual = by_code.get(record["code"])
        if record.get("deleted"):
            require(actual is None, "Una bicicleta dada de baja reapareció en el catálogo.")
            continue
        if record["expected"] is None:
            require(actual is None, "Código de bicicleta ya existente; no se adopta inventario ajeno.")
            continue
        require(actual is not None and actual.get("model") == state["marker"]
                and fingerprint(actual) == record["expected"] and actual["status"] == "AVAILABLE",
                f"La bicicleta {record['code']} cambió fuera del script. Se detiene sin modificarla.")
    for record in state.get("borrowed", []):
        if record.get("returned"):
            continue
        actual = by_code.get(record["code"])
        require(matches_identity(state, record, actual, True)
                and fingerprint(actual) == record["expected"] and actual["status"] == "AVAILABLE",
                f"La bicicleta original {record['code']} cambió fuera del script. No se traslada ni restaura automáticamente.")
    if resetting:
        return  # Retirar nuestro lote no revierte cambios ajenos en las estaciones.
    current = resolve_stations(stations, {a: s["id"] for a, s in state["stations"].items()})
    require(current == state["stations"], "Cambió una estación: revisá capacidad, ubicación o estado antes de seguir.")
    if state.get("parking"):
        parking = next((s for s in stations if s["id"] == state["parking"]["id"]), None)
        require(parking is not None and not parking.get("deletedAt") and station_snapshot(parking) == state["parking"],
                "Cambió la estación de depósito. Revisá estado y capacidad antes de seguir.")
    require(foreign_inventory(state, bikes) == state["foreign"],
            "Cambió inventario ajeno en las estaciones de la sesión. Coordiná con tus compañeros antes de continuar.")


def targets(state, scenario):
    stations = state["stations"]
    total = Counter(b["stationId"] for b in state["foreign"])
    available = Counter(b["stationId"] for b in state["foreign"] if b["status"] == "AVAILABLE")
    free = {a: s["capacity"] - total[s["id"]] for a, s in stations.items()}
    require(all(v >= 0 for v in free.values()), "El inventario supera la capacidad de una estación.")
    require(available[stations["uade"]["id"]] == 0,
            "UADE tiene bicicletas AVAILABLE ajenas. Coordiná el horario y usá preparar --trasladar-uade "
            "para trasladarlas temporalmente y devolverlas con reset.")
    require(free["planetario"] > 0 and free["urquiza"] > 0,
            "PLANETARIO y GENERAL URQUIZA necesitan al menos un anclaje libre sin nuestro lote.")
    minimum = {a: max(0, state["min_bikes"] - available[stations[a]["id"]])
               for a in ("uade", "independencia")}
    pool = sum(minimum.values()) + free["planetario"]
    require(pool <= MAX_BIKES, "El escenario requeriría más de 200 bicicletas de prueba.")
    desired = {a: 0 for a in NAMES}
    limits = dict(free)
    desired["independencia"] = minimum["independencia"]
    if scenario == "sin-bicis":
        limits["uade"] = limits["planetario"] = 0
        order = ("urquiza", "independencia")
    else:
        desired["uade"] = minimum["uade"]
        limits["urquiza"] -= 1  # Conservar lugar en la alternativa de devolución.
        if scenario == "sin-anclajes":
            desired["planetario"] = free["planetario"]
        else:
            limits["planetario"] = 0
        order = ("urquiza", "uade", "independencia")
    require(all(desired[a] <= limits[a] for a in NAMES), "No hay capacidad para el stock mínimo solicitado.")
    remaining = pool - sum(desired.values())
    for alias in order:
        amount = min(remaining, limits[alias] - desired[alias])
        desired[alias] += amount
        remaining -= amount
    require(remaining == 0, "No hay capacidad para estacionar nuestro lote en los tres escenarios. "
            "Probá --min-bikes menor o coordiná inventario con tus compañeros.")
    return desired


def new_state(api, stations, bikes, overrides, minimum, relocate_uade=False, parking_id=None):
    session = uuid.uuid4().hex[:12]
    state = {"version": 1, "url": api.url, "session": session,
             "marker": "CU-INT-01 prueba " + session, "min_bikes": minimum,
             "stations": resolve_stations(stations, overrides), "bikes": [], "pending": None,
             "phase": "preparando"}
    if relocate_uade:
        arrange_borrowing(state, stations, bikes, parking_id)
    state["foreign"] = foreign_inventory(state, bikes)
    for scenario in SCENARIOS:
        desired = targets(state, scenario)  # Comprobar los tres casos antes de crear nada.
    for index in range(sum(desired.values())):
        state["bikes"].append({"code": f"CUINT01-PRUEBA-{session}-{index + 1:03}",
                               "expected": None, "deleted": False})
    return state


def plan(state, scenario):
    desired = targets(state, scenario)
    by_id = {s["id"]: a for a, s in state["stations"].items()}
    positions = {r["code"]: by_id[r["expected"]["stationId"]] for r in state["bikes"]
                 if r["expected"] is not None and not r.get("deleted")}
    counts = Counter(positions.values())
    operations = [{"method": "PATCH", "borrowed": True, "code": r["code"], "target": state["parking"]["id"]}
                  for r in state.get("borrowed", []) if r["expected"]["stationId"] != state["parking"]["id"]]
    for record in state["bikes"]:
        if record["expected"] is None:
            alias = next(a for a in NAMES if counts[a] < desired[a])
            operations.append({"method": "POST", "code": record["code"],
                               "target": state["stations"][alias]["id"]})
            positions[record["code"]] = alias
            counts[alias] += 1
    for destination in NAMES:
        while counts[destination] < desired[destination]:
            code = next(code for code, source in positions.items() if counts[source] > desired[source])
            source = positions[code]
            operations.append({"method": "PATCH", "code": code,
                               "target": state["stations"][destination]["id"]})
            positions[code] = destination
            counts[source] -= 1
            counts[destination] += 1
    return operations


def reset_plan(state, stations, bikes):
    own_codes = {r["code"] for r in state["bikes"]}
    operations = [{"method": "DELETE", "code": r["code"]} for r in state["bikes"]
                  if r["expected"] is not None and not r.get("deleted")]
    returning = [r for r in state.get("borrowed", []) if not r.get("returned")
                 and r["expected"]["stationId"] != r["original"]["stationId"]]
    for target in {r["original"]["stationId"] for r in returning}:
        station = next((s for s in stations if s["id"] == target), None)
        require(station is not None and station.get("status") == "ACTIVE" and not station.get("deletedAt"),
                "La estación original debe estar activa para restaurar las bicicletas.")
        occupied = sum(b.get("stationId") == target and b["code"] not in own_codes for b in bikes)
        needed = sum(r["original"]["stationId"] == target for r in returning)
        require(station["capacity"] - occupied >= needed,
                "No hay lugar para devolver las bicicletas originales. Liberá anclajes en UADE antes de reset.")
    operations.extend({"method": "PATCH", "borrowed": True, "code": r["code"],
                       "target": r["original"]["stationId"]} for r in returning)
    return operations


def execute(api, store, state, operations, resetting=False):
    for operation in operations:
        stations, bikes = read_inventory(api)
        validate_inventory(state, stations, bikes, resetting)
        record = operation_record(state, operation)
        borrowed = operation.get("borrowed", False)
        require(not borrowed or operation["method"] == "PATCH", "Las bicicletas originales solo pueden trasladarse.")
        state["pending"] = operation
        store.save(state)  # Registrar antes del HTTP, incluso si luego se pierde la respuesta.
        method = operation["method"]
        if method == "POST":
            result = api.request("POST", "/api/v1/bikes", {"code": record["code"], "stationId": operation["target"],
                                 "status": "AVAILABLE", "model": state["marker"], "purchaseDate": None})
        elif method == "PATCH":
            result = api.request("PATCH", f"/api/v1/bikes/{record['expected']['id']}/station",
                                 {"stationId": operation["target"]})
        else:
            api.request("DELETE", f"/api/v1/bikes/{record['expected']['id']}")
            result = None
            record["deleted"] = True
        if result is not None:
            require(matches_identity(state, record, result, borrowed),
                    "La API devolvió una bicicleta inesperada. Revisá el estado antes de seguir.")
            # Usar la versión persistida: la precisión temporal del DTO de escritura
            # puede diferir de la que vuelve a leerse desde MySQL.
            persisted = api.request("GET", f"/api/v1/bikes/{result['id']}")
            require(matches_identity(state, record, persisted, borrowed)
                    and persisted.get("stationId") == operation["target"] and persisted.get("status") == "AVAILABLE",
                    "No se pudo confirmar la identidad persistida de la bicicleta.")
            record["expected"] = fingerprint(persisted)
            if borrowed:
                record["returned"] = operation["target"] == record["original"]["stationId"]
        state["pending"] = None
        store.save(state)


def show_inventory(state, bikes):
    own = {r["code"] for r in state["bikes"] if not r.get("deleted")}
    for station in state["stations"].values():
        present = [b for b in bikes if b.get("stationId") == station["id"]]
        available = sum(b["status"] == "AVAILABLE" for b in present)
        print(f"{station['name']} [ID interno {station['id']}]: {available} bicicletas disponibles, "
              f"{max(0, station['capacity'] - len(present))} anclajes libres; "
              f"{sum(b['code'] in own for b in present)} bicicletas de esta sesión.")
    if state.get("borrowed"):
        pending = sum(not b.get("returned") for b in state["borrowed"])
        print(f"Bicicletas originales de UADE registradas: {len(state['borrowed'])}; sin restaurar: {pending}. "
              f"Depósito: {state['parking']['name']} [ID {state['parking']['id']}].")


def check_recommendations(api, state, scenario, event_id, origin):
    station = state["stations"]["uade"]
    latitude, longitude = origin or (station["latitude"], station["longitude"])
    print(f"Consulta de retiro desde ({latitude}, {longitude}); usá el mismo origen en Espacios.")
    path = "/api/v1/stations/recommendation?" + urlencode({"lat": latitude, "lng": longitude, "purpose": "PICKUP"})
    queries = [("Retiro", path, "independencia" if scenario == "sin-bicis" else "uade")]
    if event_id:
        queries.append(("Devolución", f"/api/v1/destinations/events/{quote(event_id, safe='')}/recommendation",
                        "urquiza" if scenario == "sin-anclajes" else "planetario"))
    valid = True
    for label, path, expected in queries:
        result = api.request("GET", path)
        selected = (result.get("station") or {}).get("stationId")
        matches = result.get("status") == "RECOMMENDED" and selected == state["stations"][expected]["id"]
        print(f"{label}: {result.get('message', '')} [origen del cálculo: {result.get('source', '?')}]")
        if not matches:
            print(f"No se obtuvo {NAMES[expected]}. El algoritmo real eligió otro resultado; no se forzó la respuesta.")
        valid = valid and matches
    if not event_id:
        print("Devolución no validada: indicá --event-id con el evento activo de Espacios.")
    return valid


def run(api, store, command, apply=False, overrides=None, minimum=5, event_id=None, origin=None,
        relocate_uade=False, parking_id=None):
    state = store.load()
    require(state is None or state.get("url") == api.url, "El estado pertenece a otro backend; no se modifica ninguno.")
    profile = api.request("GET", "/api/v1/users/me")
    require(isinstance(profile, dict) and profile.get("type") == "STAFF" and profile.get("role") == "ADMIN",
            "Se necesita una cuenta STAFF con rol ADMIN en Movilidad.")
    stations, bikes = read_inventory(api)
    if state and state.get("pending"):
        recover_pending(state, bikes)
        if apply:
            store.save(state)
    if command == "preparar" and (state is None or state.get("phase") == "cerrado"):
        state = new_state(api, stations, bikes, overrides or {}, minimum, relocate_uade, parking_id)
    require(state is not None, "Primero ejecutá preparar. No hace falta crear bicicletas a mano.")
    if command == "estado":
        print(f"Sesión {state['session']}: {state['phase']} (estado local; comparar con las consultas actuales).")
        show_inventory(state, bikes)
        return True
    if state.get("phase") == "cerrado":
        require(command == "reset", "La sesión ya terminó. Ejecutá preparar para crear otro lote.")
        print("El lote de esta sesión ya fue retirado.")
        return True
    resetting = command == "reset"
    require(resetting or not (any(r.get("deleted") for r in state["bikes"])
            or any(r.get("returned") for r in state.get("borrowed", []))),
            "El reset quedó parcial: completalo antes de preparar otro escenario.")
    require(command in ("preparar", "reset") or all(r["expected"]["stationId"] == state["parking"]["id"]
            for r in state.get("borrowed", [])), "El traslado inicial quedó parcial. Completá preparar o ejecutá reset.")
    validate_inventory(state, stations, bikes, resetting)
    scenario = "exito" if command == "preparar" else command
    if resetting:
        operations = reset_plan(state, stations, bikes)
    else:
        operations = plan(state, scenario)
    print(f"Backend: {api.url}\nEscenario: {command}. Operaciones previstas: {len(operations)}.")
    if state.get("parking"):
        print(f"Traslado temporal autorizado para esta sesión: {len(state['borrowed'])} bicicletas originales de UADE. "
              f"Depósito: {state['parking']['name']} [ID {state['parking']['id']}]. reset las devuelve.")
    for operation in operations:
        print(f"  {operation['method']} {operation['code']}" +
              (f" → estación {operation['target']}" if "target" in operation else " (baja lógica)") +
              (" [original de UADE, no se elimina]" if operation.get("borrowed") else ""))
    if not apply:
        print("Vista previa: no se modificó inventario ni se guardó una sesión nueva. Usá --apply para ejecutar.")
        return True
    store.save(state)
    execute(api, store, state, operations, resetting)
    stations, bikes = read_inventory(api)
    validate_inventory(state, stations, bikes, resetting)
    if not resetting:
        desired = targets(state, scenario)
        own_codes = {r["code"] for r in state["bikes"]}
        present = Counter(b["stationId"] for b in bikes if b["code"] in own_codes)
        require(all(present[s["id"]] == desired[a] for a, s in state["stations"].items()),
                "La disponibilidad final no coincide con el plan. Revisá estado antes de continuar.")
    else:
        for record in state.get("borrowed", []):
            require(record["expected"]["stationId"] == record["original"]["stationId"],
                    "Falta devolver una bicicleta original; el reset no se considera completo.")
            record["returned"] = True
    state["phase"] = "cerrado" if resetting else scenario
    store.save(state)
    show_inventory(state, bikes)
    print("Esto no publica recomendaciones por EDA. Coordiná una inscripción/baja de inscripción en Espacios "
          "para republicar su snapshot, o esperá el recálculo de 15 minutos del CU (evento vigente y próximo).")
    return True if resetting else check_recommendations(api, state, scenario, event_id, origin)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("preparar", *SCENARIOS, "reset", "estado"))
    parser.add_argument("--base-url", default=os.environ.get("MOVILIDAD_API_URL", DEFAULT_URL))
    parser.add_argument("--state-file", type=Path, default=STATE_PATH)
    parser.add_argument("--apply", action="store_true", help="Ejecutar las escrituras mostradas en el plan.")
    parser.add_argument("--station", action="append", default=[], metavar="ALIAS=ID_INTERNO",
                        help="Desambiguar uade, independencia, planetario o urquiza. No usar IDs externos.")
    parser.add_argument("--min-bikes", type=int, default=5, help="Mínimo en UADE e INDEPENDENCIA al preparar (default: 5).")
    parser.add_argument("--trasladar-uade", action="store_true",
                        help="En preparar: registrar y trasladar temporalmente las AVAILABLE originales de UADE.")
    parser.add_argument("--deposito-id", type=int,
                        help="Estación interna para ese traslado; por defecto busca una activa con lugar, fuera del recorrido.")
    parser.add_argument("--event-id", default=os.environ.get("MOVILIDAD_EVENT_ID"),
                        help="Validar además la devolución de este evento (o MOVILIDAD_EVENT_ID).")
    parser.add_argument("--origin-lat", type=float, help="Latitud de partida usada en Espacios.")
    parser.add_argument("--origin-lng", type=float, help="Longitud de partida usada en Espacios.")
    args = parser.parse_args(argv)
    try:
        url = base_url(args.base_url)
        require(1 <= args.min_bikes <= 30, "--min-bikes debe estar entre 1 y 30.")
        require(args.deposito_id is None or (args.deposito_id > 0 and args.trasladar_uade),
                "--deposito-id requiere un ID positivo y --trasladar-uade.")
        require(not args.trasladar_uade or args.command == "preparar", "--trasladar-uade se indica solamente al preparar.")
        overrides = {}
        for item in args.station:
            alias, separator, value = item.partition("=")
            require(separator and alias in NAMES and value.isdigit() and int(value) > 0 and alias not in overrides,
                    "Usá --station ALIAS=ID_INTERNO, sin repetir alias.")
            overrides[alias] = int(value)
        origin = None
        if args.origin_lat is not None or args.origin_lng is not None:
            require(args.origin_lat is not None and args.origin_lng is not None,
                    "Indicá juntas --origin-lat y --origin-lng.")
            require(math.isfinite(args.origin_lat) and math.isfinite(args.origin_lng)
                    and abs(args.origin_lat) <= 90 and abs(args.origin_lng) <= 180, "Origen fuera de rango.")
            origin = (args.origin_lat, args.origin_lng)
        token = os.environ.get("MOVILIDAD_ADMIN_TOKEN", "").strip()
        if not token and sys.stdin.isatty():
            token = getpass.getpass("Token ADMIN de Movilidad (sin eco): ").strip()
        require(token, "Definí MOVILIDAD_ADMIN_TOKEN o ejecutá desde una terminal interactiva. No guardes el token en archivos.")
        api, store = Api(url, token), Store(args.state_file)
        if args.apply:
            with store.lock():
                success = run(api, store, args.command, True, overrides, args.min_bikes, args.event_id, origin,
                              args.trasladar_uade, args.deposito_id)
        else:
            success = run(api, store, args.command, False, overrides, args.min_bikes, args.event_id, origin,
                          args.trasladar_uade, args.deposito_id)
        return 0 if success else 2
    except (DemoError, OSError, KeyError, TypeError, StopIteration) as error:
        # Errores inesperados de formatos no deben mostrar respuestas ni secretos.
        message = str(error) if isinstance(error, DemoError) else "Formato/estado local inesperado; conservá el archivo de sesión."
        print("ERROR: " + message, file=sys.stderr)
        if args.apply:
            print("Si hubo escrituras, pueden ser parciales: revisá estado y luego reset. No borres el archivo de sesión.", file=sys.stderr)
        else:
            print("Esta ejecución fue una vista previa: no modificó inventario.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
