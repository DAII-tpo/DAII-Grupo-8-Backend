"""Pruebas sin Render, credenciales reales, base compartida ni publicaciones EDA."""

from contextlib import redirect_stdout
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo_cu_int_01 as demo


class FakeApi:
    """Contrato HTTP administrativo: capacidad, bajas lógicas y updatedAt."""
    def __init__(self):
        self.url = "http://localhost:8080"
        self.stations = [{"id": i, "name": name, "capacity": capacity, "status": "ACTIVE",
                          "latitude": -34.6, "longitude": -58.4, "deletedAt": None}
                         for i, name, capacity in [(10, "UADE", 16), (20, "INDEPENDENCIA", 20),
                                                   (30, "PLANETARIO", 7), (40, "GENERAL URQUIZA", 20)]]
        self.bikes = []
        self.calls = []
        self.clock = 0
        self.profile = {"type": "STAFF", "role": "ADMIN"}
        self.lose_response = None
        self.selected_pickup = None

    def stock(self, station_id):
        return [b for b in self.bikes if b["stationId"] == station_id]

    def available(self, station_id):
        return sum(b["status"] == "AVAILABLE" for b in self.stock(station_id))

    def request(self, method, path, body=None):
        self.calls.append((method, path, deepcopy(body)))
        if method == "GET":
            if path == "/api/v1/users/me":
                return deepcopy(self.profile)
            if path == "/api/v1/stations":
                return deepcopy(self.stations)
            if path == "/api/v1/bikes":
                return deepcopy(self.bikes)
            if path.startswith("/api/v1/bikes/"):
                return deepcopy(next(b for b in self.bikes if b["id"] == int(path.rsplit("/", 1)[1])))
            if path.startswith("/api/v1/stations/recommendation?"):
                selected = self.selected_pickup or (10 if self.available(10) else 20)
            elif path.startswith("/api/v1/destinations/events/"):
                selected = 30 if len(self.stock(30)) < 7 else 40
            else:
                raise AssertionError(f"GET no esperado: {path}")
            return {"status": "RECOMMENDED", "station": {"stationId": selected},
                    "message": "Resultado calculado por la API", "source": "FALLBACK"}
        self.clock += 1
        if method == "POST":
            if any(b["code"] == body["code"] for b in self.bikes):
                raise demo.DemoError("409 código duplicado")
            result = dict(body, id=self.clock, updatedAt=f"v{self.clock}")
            self.bikes.append(result)
        else:
            bike_id = int(path.split("/")[4])
            result = next(b for b in self.bikes if b["id"] == bike_id)
            if result["status"] == "IN_USE":
                raise demo.DemoError("409 bicicleta en uso")
            if method == "PATCH":
                result["stationId"] = body["stationId"]
                result["updatedAt"] = f"v{self.clock}"
            elif method == "DELETE":
                self.bikes.remove(result)
            else:
                raise AssertionError(f"Método inesperado: {method}")
        if method != "DELETE":
            station = next(s for s in self.stations if s["id"] == result["stationId"])
            if len(self.stock(station["id"])) > station["capacity"]:
                raise AssertionError("El script sobrepasó la capacidad")
        if self.lose_response == method:
            self.lose_response = None
            raise demo.DemoError("Respuesta perdida después de confirmar la escritura")
        response = deepcopy(result)
        # Simula que la respuesta de escritura tiene distinta precisión temporal.
        response["updatedAt"] = "timestamp-de-escritura"
        return response if method != "DELETE" else None


class ScenariosTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = demo.Store(Path(self.tmp.name) / "state.json")
        self.api = FakeApi()
        self.output = io.StringIO()

    def run_scenario(self, command, apply=True, **kwargs):
        with redirect_stdout(self.output):
            return demo.run(self.api, self.store, command, apply, **kwargs)

    def writes(self):
        return [c for c in self.api.calls if c[0] != "GET"]

    def original_uade_stock(self, count=2):
        self.api.stations.append({"id": 50, "name": "DEPÓSITO ACORDADO", "capacity": 30,
                                  "status": "ACTIVE", "latitude": -34.7, "longitude": -58.5})
        originals = [{"id": 900 + i, "code": f"ECO-UADE-{i}", "model": "Stock previo",
                      "stationId": 10, "status": "AVAILABLE", "updatedAt": f"original-{i}"}
                     for i in range(count)]
        self.api.bikes.extend(deepcopy(originals))
        return originals

    def assert_originals_restored(self, originals):
        actual = {b["code"]: b for b in self.api.bikes}
        self.assertEqual(set(actual), {b["code"] for b in originals})
        for expected in originals:
            for key in ("id", "code", "model", "stationId", "status"):
                self.assertEqual(actual[expected["code"]][key], expected[key])

    def test_preview_does_not_create_data_or_state(self):
        self.assertTrue(self.run_scenario("preparar", apply=False))
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.store.path.exists())

    def test_three_scenarios_and_reset_only_use_our_bikes(self):
        foreign = {"id": 900, "code": "OTRO-GRUPO-1", "model": "Otra prueba",
                   "stationId": 20, "status": "AVAILABLE", "updatedAt": "original"}
        self.api.bikes.append(deepcopy(foreign))
        self.run_scenario("preparar", event_id="evento-de-prueba")
        original_codes = {b["code"] for b in self.api.bikes}
        self.assertGreaterEqual(self.api.available(10), 5)
        self.assertLess(len(self.api.stock(30)), 7)
        self.run_scenario("sin-anclajes", event_id="evento-de-prueba")
        self.assertEqual(len(self.api.stock(30)), 7)
        self.assertLess(len(self.api.stock(40)), 20)
        self.run_scenario("sin-bicis", event_id="evento-de-prueba")
        self.assertEqual(self.api.available(10), 0)
        self.assertGreaterEqual(self.api.available(20), 5)
        self.assertEqual(len(self.api.stock(30)), 0)
        self.run_scenario("exito", event_id="evento-de-prueba")
        self.assertGreaterEqual(self.api.available(10), 5)
        self.assertEqual({b["code"] for b in self.api.bikes}, original_codes)
        self.run_scenario("reset")
        self.assertEqual(self.api.bikes, [foreign])
        self.assertEqual(self.store.load()["phase"], "cerrado")
        self.assertFalse(any("/events" in path and method != "GET" for method, path, _ in self.writes()))

    def test_repeat_prepare_and_scenario_are_idempotent(self):
        self.run_scenario("preparar")
        self.api.calls.clear()
        self.run_scenario("preparar")
        self.assertEqual(self.writes(), [])
        self.run_scenario("sin-anclajes")
        self.api.calls.clear()
        self.run_scenario("sin-anclajes")
        self.assertEqual(self.writes(), [])

    def test_new_session_after_reset_has_new_codes(self):
        self.run_scenario("preparar")
        first_codes = {r["code"] for r in self.store.load()["bikes"]}
        self.run_scenario("reset")
        self.run_scenario("reset")
        self.run_scenario("preparar")
        self.assertTrue(first_codes.isdisjoint(r["code"] for r in self.store.load()["bikes"]))

    def test_foreign_available_bikes_in_uade_abort_before_writes(self):
        self.api.bikes.append({"id": 900, "code": "AJENA", "stationId": 10, "status": "AVAILABLE"})
        with self.assertRaisesRegex(demo.DemoError, "UADE.*ajenas"):
            self.run_scenario("preparar")
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.store.path.exists())

    def test_borrowing_preview_lists_originals_without_moving_them(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", apply=False, relocate_uade=True)
        self.assert_originals_restored(originals)
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.store.path.exists())
        self.assertIn("original de UADE, no se elimina", self.output.getvalue())
        self.assertIn("DEPÓSITO ACORDADO", self.output.getvalue())

    def test_borrowed_bikes_survive_full_cycle_and_return_to_uade(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.assertTrue(all(b["stationId"] == 50 for b in self.api.bikes if b["id"] >= 900))
        for scenario in demo.SCENARIOS:
            self.run_scenario(scenario)
        self.assertEqual(self.api.available(10), 0)
        self.run_scenario("reset")
        self.assert_originals_restored(originals)
        for method, path, _ in self.writes():
            if method == "DELETE":
                self.assertLess(int(path.rsplit("/", 1)[1]), 900)
            self.assertFalse(path.endswith("/status"))
        self.assertTrue(all(r["returned"] for r in self.store.load()["borrowed"]))

    def test_borrowing_leaves_unavailable_originals_in_place(self):
        originals = self.original_uade_stock()
        maintenance = {"id": 950, "code": "EN-MANTENIMIENTO", "stationId": 10,
                       "status": "MAINTENANCE", "model": "Otra", "updatedAt": "intacto"}
        self.api.bikes.append(deepcopy(maintenance))
        self.run_scenario("preparar", relocate_uade=True)
        self.run_scenario("sin-bicis")
        self.assertEqual(self.api.available(10), 0)
        self.assertIn(maintenance, self.api.bikes)
        self.run_scenario("reset")
        self.assertIn(maintenance, self.api.bikes)
        self.api.bikes.remove(maintenance)
        self.assert_originals_restored(originals)

    def test_borrowing_requires_safe_parking_before_any_write(self):
        self.original_uade_stock()
        self.api.stations[-1]["capacity"] = 1
        with self.assertRaisesRegex(demo.DemoError, "depósito"):
            self.run_scenario("preparar", relocate_uade=True)
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.store.path.exists())

    def test_parking_cannot_be_one_of_the_four_stations_or_near_the_route(self):
        self.original_uade_stock()
        with self.assertRaises(demo.DemoError):
            self.run_scenario("preparar", relocate_uade=True, parking_id=40)
        self.api.stations[-1]["latitude"] = -34.6
        self.api.stations[-1]["longitude"] = -58.4
        with self.assertRaises(demo.DemoError):
            self.run_scenario("preparar", relocate_uade=True, parking_id=50)
        self.assertEqual(self.writes(), [])

    def test_lost_borrow_response_can_resume_without_duplicate_transfers(self):
        originals = self.original_uade_stock()
        self.api.lose_response = "PATCH"
        with self.assertRaises(demo.DemoError):
            self.run_scenario("preparar", relocate_uade=True)
        self.assertTrue(self.store.load()["pending"]["borrowed"])
        self.run_scenario("preparar")
        self.assertEqual(sum(m == "PATCH" and p == "/api/v1/bikes/900/station" for m, p, _ in self.writes()), 1)
        self.run_scenario("reset")
        self.assert_originals_restored(originals)

    def test_partial_borrowing_can_be_reset_without_finishing_preparation(self):
        originals = self.original_uade_stock()
        self.api.lose_response = "PATCH"
        with self.assertRaises(demo.DemoError):
            self.run_scenario("preparar", relocate_uade=True)
        with self.assertRaisesRegex(demo.DemoError, "Completá preparar"):
            self.run_scenario("sin-bicis")
        self.run_scenario("reset")
        self.assert_originals_restored(originals)

    def test_lost_return_response_can_finish_reset(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.api.lose_response = "PATCH"
        with self.assertRaises(demo.DemoError):
            self.run_scenario("reset")
        self.run_scenario("reset")
        self.assert_originals_restored(originals)
        self.assertEqual(sum(m == "PATCH" and p == "/api/v1/bikes/900/station" for m, p, _ in self.writes()), 2)

    def test_uncommitted_borrow_and_return_can_be_retried(self):
        originals = self.original_uade_stock()
        request = self.api.request

        def fail_transfer(method, path, body=None):
            if method == "PATCH":
                raise demo.DemoError("Traslado no confirmado")
            return request(method, path, body)

        for command in ("preparar", "reset"):
            with self.subTest(command=command):
                with patch.object(self.api, "request", side_effect=fail_transfer):
                    with self.assertRaisesRegex(demo.DemoError, "no confirmado"):
                        self.run_scenario(command, relocate_uade=command == "preparar")
                self.assertTrue(self.store.load()["pending"]["borrowed"])
                self.run_scenario(command)
        self.assert_originals_restored(originals)

    def test_partial_reset_without_test_pool_must_finish_before_preparing_again(self):
        originals = self.original_uade_stock()
        request = self.api.request

        def fail_create(method, path, body=None):
            if method == "POST":
                raise demo.DemoError("Alta no confirmada")
            return request(method, path, body)

        with patch.object(self.api, "request", side_effect=fail_create):
            with self.assertRaisesRegex(demo.DemoError, "Alta no confirmada"):
                self.run_scenario("preparar", relocate_uade=True)
        self.assertTrue(all(b["stationId"] == 50 for b in self.api.bikes))

        def fail_second_return(method, path, body=None):
            if method == "PATCH" and path == "/api/v1/bikes/901/station":
                raise demo.DemoError("Segundo regreso no confirmado")
            return request(method, path, body)

        with patch.object(self.api, "request", side_effect=fail_second_return):
            with self.assertRaisesRegex(demo.DemoError, "Segundo regreso"):
                self.run_scenario("reset")
        self.assertTrue(self.store.load()["borrowed"][0]["returned"])
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "reset quedó parcial"):
            self.run_scenario("preparar")
        self.assertEqual(self.writes(), [])
        self.run_scenario("reset")
        self.assert_originals_restored(originals)

    def test_missing_original_is_not_recreated_or_adopted(self):
        self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.api.bikes[:] = [b for b in self.api.bikes if b["id"] != 900]
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "original.*cambió"):
            self.run_scenario("reset")
        self.assertEqual(self.writes(), [])

    def test_inactive_original_station_blocks_reset_before_writes(self):
        self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True, parking_id=50)
        self.api.stations[0]["status"] = "INACTIVE"
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "original debe estar activa"):
            self.run_scenario("reset")
        self.assertEqual(self.writes(), [])

    def test_borrowed_bike_in_use_or_modified_blocks_reset_before_deleting_our_pool(self):
        self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        next(b for b in self.api.bikes if b["id"] == 900)["status"] = "IN_USE"
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "original.*cambió"):
            self.run_scenario("reset")
        self.assertEqual(self.writes(), [])

    def test_reset_checks_return_capacity_before_deleting_our_pool(self):
        originals = self.original_uade_stock(count=16)
        self.run_scenario("preparar", relocate_uade=True)
        outsider = {"id": 999, "code": "NUEVA-AJENA", "model": "Otra", "stationId": 10,
                    "status": "AVAILABLE", "updatedAt": "externo"}
        self.api.bikes.append(outsider)
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "No hay lugar.*devolver"):
            self.run_scenario("reset")
        self.assertEqual(self.writes(), [])
        self.api.bikes.remove(outsider)
        self.run_scenario("reset")
        self.assert_originals_restored(originals)

    def test_parking_change_stops_scenario_but_does_not_get_reverted_by_reset(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        outsider = {"id": 999, "code": "AJENA-DEPOSITO", "stationId": 50,
                    "status": "AVAILABLE", "updatedAt": "externo"}
        self.api.bikes.append(outsider)
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "inventario ajeno"):
            self.run_scenario("sin-anclajes")
        self.assertEqual(self.writes(), [])
        self.run_scenario("reset")
        self.assertIn(outsider, self.api.bikes)
        self.api.bikes.remove(outsider)
        self.assert_originals_restored(originals)

    def test_no_parking_capacity_aborts_before_writes(self):
        self.api.stations[1]["capacity"] = 5
        self.api.stations[3]["capacity"] = 1
        with self.assertRaises(demo.DemoError):
            self.run_scenario("preparar")
        self.assertEqual(self.writes(), [])

    def test_no_admin_aborts_before_writes(self):
        for profile in ({"type": "CITIZEN", "role": "ADMIN"}, {"type": "STAFF", "role": "USER"}, None):
            self.api.profile = profile
            with self.assertRaisesRegex(demo.DemoError, "ADMIN"):
                self.run_scenario("preparar")
        self.assertEqual(self.writes(), [])

    def test_ambiguous_name_requires_explicit_internal_id(self):
        self.api.stations.append(dict(self.api.stations[0], id=999))
        with self.assertRaisesRegex(demo.DemoError, "única"):
            self.run_scenario("preparar")
        self.run_scenario("preparar", overrides={"uade": 10})
        self.assertEqual(self.store.load()["stations"]["uade"]["id"], 10)

    def test_wrong_station_id_cannot_change_another_station(self):
        with self.assertRaisesRegex(demo.DemoError, "no corresponde"):
            self.run_scenario("preparar", overrides={"uade": 20})
        self.assertEqual(self.writes(), [])

    def test_foreign_change_stops_scenario_but_reset_preserves_it(self):
        self.run_scenario("preparar")
        foreign = {"id": 900, "code": "AJENA-NUEVA", "stationId": 30, "status": "AVAILABLE"}
        self.api.bikes.append(deepcopy(foreign))
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "inventario ajeno"):
            self.run_scenario("sin-anclajes")
        self.assertEqual(self.writes(), [])
        self.run_scenario("reset")
        self.assertEqual(self.api.bikes, [foreign])

    def test_station_change_stops_scenario_without_overwriting_capacity(self):
        self.run_scenario("preparar")
        self.api.stations[0]["capacity"] = 17
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "Cambió una estación"):
            self.run_scenario("sin-anclajes")
        self.assertEqual(self.writes(), [])

    def test_borrowed_or_moved_test_bike_is_not_touched_by_reset(self):
        self.run_scenario("preparar")
        original = deepcopy(self.api.bikes[0])
        for change in ({"status": "IN_USE"}, {"updatedAt": "cambio-ajeno"}):
            with self.subTest(change=change):
                self.api.bikes[0] = dict(original, **change)
                self.api.calls.clear()
                with self.assertRaisesRegex(demo.DemoError, "cambió fuera"):
                    self.run_scenario("reset")
                self.assertEqual(self.writes(), [])

    def test_lost_post_response_can_resume_without_duplicate_bikes(self):
        self.api.lose_response = "POST"
        with self.assertRaisesRegex(demo.DemoError, "Respuesta perdida"):
            self.run_scenario("preparar")
        self.assertEqual(len(self.api.bikes), 1)
        self.assertIsNotNone(self.store.load()["pending"])
        self.run_scenario("preparar")
        self.assertEqual(len(self.api.bikes), len(self.store.load()["bikes"]))
        self.assertEqual(len({b["code"] for b in self.api.bikes}), len(self.api.bikes))

    def test_lost_transfer_response_can_resume(self):
        self.run_scenario("preparar")
        self.api.lose_response = "PATCH"
        with self.assertRaises(demo.DemoError):
            self.run_scenario("sin-anclajes")
        self.run_scenario("sin-anclajes")
        self.assertEqual(len(self.api.stock(30)), 7)

    def test_lost_delete_response_can_finish_reset(self):
        self.run_scenario("preparar")
        self.api.lose_response = "DELETE"
        with self.assertRaises(demo.DemoError):
            self.run_scenario("reset")
        self.run_scenario("reset")
        self.assertEqual(self.api.bikes, [])

    def test_failed_delete_before_commit_can_be_retried(self):
        self.run_scenario("preparar")
        self.api.request = Mock(wraps=self.api.request)
        original = self.api.request._mock_wraps
        def fail_once(method, path, body=None):
            if method == "DELETE":
                self.api.request.side_effect = None
                raise demo.DemoError("No se confirmó la baja")
            return original(method, path, body)
        self.api.request.side_effect = fail_once
        with self.assertRaises(demo.DemoError):
            self.run_scenario("reset")
        self.run_scenario("reset")
        self.assertEqual(self.api.bikes, [])

    def test_backend_binding_prevents_reset_on_another_service(self):
        self.run_scenario("preparar")
        self.api.url = "https://otro-backend.example"
        self.api.calls.clear()
        with self.assertRaisesRegex(demo.DemoError, "otro backend"):
            self.run_scenario("reset")
        self.assertEqual(self.api.calls, [])

    def test_real_algorithm_result_is_not_replaced_by_expected_station(self):
        self.api.selected_pickup = 999
        self.assertFalse(self.run_scenario("preparar", event_id="evento/a"))
        self.assertIn("no se forzó", self.output.getvalue())
        self.assertTrue(any("evento%2Fa/recommendation" in p for _, p, _ in self.api.calls))
        self.assertEqual(self.store.load()["phase"], "exito")

    def test_script_never_changes_station_fields_or_bike_status(self):
        for scenario in ("preparar", *demo.SCENARIOS, "reset"):
            self.run_scenario(scenario)
        for method, path, body in self.writes():
            self.assertTrue(path.startswith("/api/v1/bikes"))
            self.assertFalse(path.endswith("/status"))
            if method == "PATCH":
                self.assertEqual(set(body), {"stationId"})
        self.assertNotIn("token", self.store.path.read_text().lower())

    def test_lock_prevents_two_local_writers(self):
        with self.store.lock():
            with self.assertRaisesRegex(demo.DemoError, "otra ejecución"):
                with self.store.lock():
                    self.fail("Se permitió un segundo escritor")


class HttpTest(unittest.TestCase):
    def test_cli_full_cycle_over_http_uses_existing_admin_endpoints(self):
        self.cli_cycle(relocate_uade=False)

    def test_cli_borrowing_and_reset_restore_originals_over_http(self):
        self.cli_cycle(relocate_uade=True)

    def cli_cycle(self, relocate_uade):
        backend = FakeApi()
        if relocate_uade:
            backend.stations.append({"id": 50, "name": "DEPÓSITO", "capacity": 30, "status": "ACTIVE",
                                     "latitude": -34.7, "longitude": -58.5})
            backend.bikes.append({"id": 900, "code": "UADE-ORIGINAL-HTTP", "stationId": 10,
                                  "model": "Existente", "status": "AVAILABLE", "updatedAt": "original"})
        originals = deepcopy(backend.bikes)

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def dispatch(self):
                if self.headers.get("Authorization") != "Bearer TEST-HTTP-TOKEN":
                    self.send_error(401)
                    return
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length)) if length else None
                result = backend.request(self.command, self.path, body)
                raw = json.dumps(result).encode() if result is not None else b""
                self.send_response(204 if self.command == "DELETE" else 200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_GET = do_POST = do_PATCH = do_DELETE = dispatch

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"MOVILIDAD_ADMIN_TOKEN": "TEST-HTTP-TOKEN"}):
                state_path = str(Path(directory) / "state.json")
                for command in ("preparar", "sin-anclajes", "sin-bicis", "exito", "reset"):
                    with self.subTest(command=command), redirect_stdout(io.StringIO()):
                        flags = ["--trasladar-uade"] if command == "preparar" and relocate_uade else []
                        exit_code = demo.main([command, "--apply", "--state-file", state_path,
                                               "--base-url", f"http://127.0.0.1:{server.server_port}",
                                               "--event-id", "evento-de-prueba", *flags])
                        self.assertEqual(exit_code, 0)
                        if command == "sin-bicis":
                            self.assertEqual(backend.available(10), 0)
                        elif command != "reset":
                            self.assertGreaterEqual(backend.available(10), 5)
                state = json.loads(Path(state_path).read_text())
                self.assertEqual(state["phase"], "cerrado")
                self.assertEqual(len(backend.bikes), len(originals))
                for actual, expected in zip(backend.bikes, originals):
                    for key in ("id", "code", "model", "stationId", "status"):
                        self.assertEqual(actual[key], expected[key])
                self.assertNotIn("TEST-HTTP-TOKEN", Path(state_path).read_text())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_rejects_credentials_query_paths_and_non_local_http(self):
        for value in ("http://example.com", "https://user:pass@example.com", "https://example.com/api/v1",
                      "https://example.com?token=x", "https://example.com/#x", "https://[", "https://example.com:port"):
            with self.subTest(url=value), self.assertRaises(demo.DemoError):
                demo.base_url(value)

    def test_http_error_does_not_expose_body_or_bearer(self):
        api = demo.Api("https://example.com", "TEST-TOKEN-NOT-REAL")
        api.opener = Mock()
        api.opener.open.side_effect = HTTPError("https://example.com", 401, "Unauthorized", {},
                                                io.BytesIO(b"TEST-TOKEN-NOT-REAL"))
        with self.assertRaises(demo.DemoError) as caught:
            api.request("GET", "/api/v1/users/me")
        self.assertIn("401", str(caught.exception))
        self.assertNotIn("TEST-TOKEN", str(caught.exception))

    def test_redirect_is_rejected(self):
        with self.assertRaisesRegex(demo.DemoError, "no se reenvió"):
            demo.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://another.example")


if __name__ == "__main__":
    unittest.main()
