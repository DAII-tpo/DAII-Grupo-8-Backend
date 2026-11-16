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
        backend = FakeApi()

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
                        exit_code = demo.main([command, "--apply", "--state-file", state_path,
                                               "--base-url", f"http://127.0.0.1:{server.server_port}",
                                               "--event-id", "evento-de-prueba"])
                        self.assertEqual(exit_code, 0)
                state = json.loads(Path(state_path).read_text())
                self.assertEqual(state["phase"], "cerrado")
                self.assertEqual(backend.bikes, [])
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
