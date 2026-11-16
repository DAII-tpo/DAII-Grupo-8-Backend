"""Pruebas sin Render, credenciales reales, base compartida ni publicaciones EDA."""

from contextlib import redirect_stderr, redirect_stdout
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


def console_inputs(values):
    """Falla si el menú pide más entradas de las previstas, en vez de quedar en un bucle."""
    values = iter(values)

    def read(_prompt):
        try:
            value = next(values)
        except StopIteration:
            raise AssertionError("El menú solicitó una entrada inesperada después del flujo de prueba") from None
        if isinstance(value, BaseException):
            raise value
        return value

    return read


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

    def run_menu(self, inputs, login_effect=None):
        output, errors = io.StringIO(), io.StringIO()
        with patch.dict("os.environ", {"MOVILIDAD_ADMIN_TOKEN": "STALE-IGNORED-TOKEN"}), \
                patch("sys.stdin.isatty", return_value=True), patch("builtins.input", side_effect=console_inputs(inputs)), \
                patch.object(demo, "Api", return_value=self.api), \
                patch.object(demo, "prompt_login", side_effect=login_effect) as login, \
                redirect_stdout(output), redirect_stderr(errors):
            code = demo.main(["--state-file", str(self.store.path), "--base-url", self.api.url])
        return code, output.getvalue(), errors.getvalue(), login.call_count

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

    def test_zero_does_not_exit_menu_and_reset_restores_stock_before_closing(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        code, output, errors, logins = self.run_menu(["invalid", "0", "5", "6"])
        self.assertEqual(code, 0)
        self.assertEqual(errors, "")
        self.assertEqual(logins, 1)
        self.assert_originals_restored(originals)
        self.assertEqual(self.store.load()["phase"], "cerrado")
        self.assertIn("Opción inválida", output)
        self.assertIn("El menú solo se cierra después de restaurar", output)
        self.assertIn("Reset confirmado", output)

    def test_login_failure_keeps_menu_open_for_another_login_and_reset(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        code, output, errors, logins = self.run_menu(["8", "5", "6"],
                                                   [demo.ApiError(403, "POST", "/auth/login"), None])
        self.assertEqual(code, 0)
        self.assertEqual(logins, 2)
        self.assertIn("403", errors)
        self.assertIn("El menú sigue abierto", output)
        self.assert_originals_restored(originals)

    def test_partial_reset_keeps_menu_open_until_a_retry_completes_it(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.api.lose_response = "DELETE"
        code, output, errors, _ = self.run_menu(["6", "5", "6"])
        self.assertEqual(code, 0)
        self.assertIn("Respuesta perdida", errors)
        self.assertIn("El menú sigue abierto", output)
        self.assertGreaterEqual(output.count("CU-INT-01 — Menú de la demo"), 3)
        self.assert_originals_restored(originals)
        self.assertEqual(self.store.load()["phase"], "cerrado")

    def test_ctrl_c_restores_original_stock_before_closing(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        code, output, errors, _ = self.run_menu([KeyboardInterrupt()])
        self.assertEqual(code, 0)
        self.assertEqual(errors, "")
        self.assertIn("Ejecutando reset antes de salir", output)
        self.assert_originals_restored(originals)

    def test_ctrl_c_with_failed_reset_returns_to_menu_until_reset_succeeds(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.api.lose_response = "DELETE"
        code, output, errors, _ = self.run_menu([KeyboardInterrupt(), "6"])
        self.assertEqual(code, 0)
        self.assertIn("Respuesta perdida", errors)
        self.assertIn("El menú sigue abierto", output)
        self.assert_originals_restored(originals)

    def test_disconnected_input_attempts_reset_before_closing(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        code, _, errors, _ = self.run_menu([EOFError()])
        self.assertEqual(code, 0)
        self.assertEqual(errors, "")
        self.assert_originals_restored(originals)

    def test_disconnected_input_with_failed_reset_preserves_recovery_file_and_reports_failure(self):
        originals = self.original_uade_stock()
        self.run_scenario("preparar", relocate_uade=True)
        self.api.lose_response = "DELETE"
        code, output, errors, _ = self.run_menu([EOFError()])
        self.assertEqual(code, 1)
        self.assertIn("no se pudo confirmar reset", errors)
        self.assertNotIn("Reset confirmado", output)
        self.assertIsNotNone(self.store.load()["pending"])
        self.assertNotEqual(self.store.load()["phase"], "cerrado")
        self.run_scenario("reset")
        self.assert_originals_restored(originals)

    def test_reset_without_prepared_inventory_can_close_after_failed_login(self):
        code, output, _, _ = self.run_menu(["6"], demo.ApiError(403, "POST", "/auth/login"))
        self.assertEqual(code, 0)
        self.assertIn("No hay cambios pendientes", output)
        self.assertFalse(self.store.path.exists())
        self.assertEqual(self.writes(), [])

    def test_reset_cannot_announce_success_without_confirmed_closed_state(self):
        self.run_scenario("preparar")
        with patch.object(demo, "run", return_value=True), redirect_stdout(self.output):
            with self.assertRaisesRegex(demo.DemoError, "Todavía quedan cambios pendientes"):
                demo.reset_and_close(self.api, self.store)
        self.assertNotEqual(self.store.load()["phase"], "cerrado")
        self.run_scenario("reset")

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

    def test_interactive_login_menu_and_reset_over_http(self):
        self.cli_cycle(relocate_uade=True, interactive=True)

    def test_menu_with_web_token_uses_backend_role_and_never_calls_idp(self):
        self.cli_cycle(relocate_uade=True, interactive=True, token_login=True)

    def test_rejected_login_keeps_http_menu_open_to_import_session_and_restore(self):
        self.cli_cycle(relocate_uade=True, interactive=True, reject_login=True)

    def cli_cycle(self, relocate_uade, interactive=False, token_login=False, reject_login=False):
        backend = FakeApi()
        auth_calls = []
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
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length)) if length else None
                if self.path == "/auth/login":
                    auth_calls.append((body, self.headers.get("Authorization")))
                    if token_login or reject_login:
                        self.send_error(403)
                        return
                    result = {"access_token": "TEST-HTTP-TOKEN", "refresh_token": "TEST-REFRESH-TOKEN",
                              "expires_in": 3600, "token_type": "Bearer"}
                else:
                    if self.headers.get("Authorization") != "Bearer TEST-HTTP-TOKEN":
                        self.send_error(401)
                        return
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
            with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {
                    "MOVILIDAD_ADMIN_TOKEN": "STALE-IGNORED-TOKEN" if interactive else "TEST-HTTP-TOKEN"}):
                state_path = str(Path(directory) / "state.json")
                root = f"http://127.0.0.1:{server.server_port}"
                if interactive:
                    output, errors = io.StringIO(), io.StringIO()
                    inputs = ([] if token_login else ["admin-demo", "TEST-PASSWORD"])
                    inputs += ["10"] if reject_login else []
                    inputs += ["9", "7", "evento-de-prueba", "1", "3"]
                    inputs += ["10"] if token_login else []
                    inputs += ["4", "2", "5", "6"]
                    with patch("sys.stdin.isatty", return_value=True), \
                            patch("builtins.input", side_effect=console_inputs(inputs)) as prompts, \
                            patch.object(demo.getpass, "getpass", return_value="TEST-HTTP-TOKEN") as token_prompt, \
                            redirect_stdout(output), redirect_stderr(errors):
                        flags = ["--usar-token"] if token_login else []
                        exit_code = demo.main(["--state-file", state_path, "--base-url", root, "--auth-url", root, *flags])
                    if reject_login:
                        self.assertIn("API HTTP 403", errors.getvalue())
                        self.assertIn("El menú sigue abierto", output.getvalue())
                    else:
                        self.assertEqual(errors.getvalue(), "")
                    self.assertEqual(exit_code, 0)
                    if token_login:
                        self.assertEqual(auth_calls, [])
                        self.assertEqual(token_prompt.call_count, 2)
                        self.assertIn("Sesión web ADMIN validada", output.getvalue())
                    else:
                        self.assertIn("Contraseña (visible en consola)", prompts.call_args_list[1].args[0])
                        self.assertEqual(auth_calls, [({"username": "admin-demo", "password": "TEST-PASSWORD",
                                                       "clientId": demo.DEFAULT_CLIENT_ID}, None)])
                        if reject_login:
                            token_prompt.assert_called_once()
                        else:
                            token_prompt.assert_not_called()
                    self.assertIn("Vista previa", output.getvalue())
                    self.assertIn("PLANETARIO", output.getvalue())
                    for secret in ("TEST-PASSWORD", "TEST-HTTP-TOKEN", "TEST-REFRESH-TOKEN"):
                        self.assertNotIn(secret, output.getvalue() + errors.getvalue() + Path(state_path).read_text())
                    # Solo los dos traslados normales de la original: al depósito y de vuelta.
                    self.assertEqual(sum(m == "PATCH" and p == "/api/v1/bikes/900/station"
                                         for m, p, _ in backend.calls), 2)
                else:
                    for command in ("preparar", "sin-anclajes", "sin-bicis", "exito", "reset"):
                        with self.subTest(command=command), redirect_stdout(io.StringIO()):
                            flags = ["--trasladar-uade"] if command == "preparar" and relocate_uade else []
                            exit_code = demo.main([command, "--apply", "--state-file", state_path,
                                                   "--base-url", root, "--event-id", "evento-de-prueba", *flags])
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


class SessionTest(unittest.TestCase):
    @staticmethod
    def tokens(access="access-1", refresh="refresh-1"):
        return {"access_token": access, "refresh_token": refresh, "expires_in": 3600, "token_type": "Bearer"}

    def test_login_explains_known_anomaly_block_without_exposing_remote_details(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.opener = Mock()
        body = json.dumps({"status": 403, "error": "Acceso bloqueado",
                           "message": "Acceso bloqueado por actividad inusual",
                           "password": "SECRET-PASSWORD", "token": "SECRET-TOKEN"}).encode()
        session.opener.open.side_effect = HTTPError(session.url, 403, "Forbidden", {}, io.BytesIO(body))
        with self.assertRaises(demo.ApiError) as caught:
            session.login("admin-demo", "SECRET-PASSWORD")
        self.assertEqual(caught.exception.reason, "anomaly")
        self.assertIn("actividad inusual", str(caught.exception))
        self.assertNotIn("SECRET", str(caught.exception))
        self.assertEqual(session.opener.open.call_count, 1)
        self.assertIsNone(session.access_token)

    def test_unknown_login_403_does_not_invent_a_cause_or_expose_its_body(self):
        for body, reason in ((b'<html>SECRET-RESPONSE</html>', "html"),
                             (b'{"message":"SECRET-RESPONSE"}', "json")):
            with self.subTest(body=body):
                session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
                session.opener = Mock()
                session.opener.open.side_effect = HTTPError(session.url, 403, "Forbidden", {}, io.BytesIO(body))
                with self.assertRaises(demo.ApiError) as caught:
                    session.login("admin-demo", "SECRET-PASSWORD")
                self.assertEqual(caught.exception.reason, reason)
                self.assertNotIn("actividad inusual", str(caught.exception))
                self.assertNotIn("SECRET", str(caught.exception))

    def test_cloudflare_rejection_recognized_only_with_known_evidence(self):
        for headers, body, reason in (({"cf-mitigated": "challenge"}, b"SECRET", "cloudflare_challenge"),
                                      ({"Server": "cloudflare"}, b"error code: 1010", "cloudflare_1010"),
                                      ({"cf-ray": "SECRET-RAY"}, b"error code: 1020", "cloudflare_1020"),
                                      ({"Server": "cloudflare"}, b"SECRET", "text"),
                                      ({}, b"error code: 1010", "text")):
            with self.subTest(reason=reason):
                session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
                session.opener = Mock()
                session.opener.open.side_effect = HTTPError(session.url, 403, "Forbidden", headers, io.BytesIO(body))
                with self.assertRaises(demo.ApiError) as caught:
                    session.login("admin-demo", "SECRET-PASSWORD")
                self.assertEqual(caught.exception.reason, reason)
                self.assertNotIn("SECRET", str(caught.exception))

    def test_plain_cloudflare_header_does_not_override_idp_anomaly_category(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.opener = Mock()
        body = b'{"error":"Acceso bloqueado","message":"Acceso bloqueado por actividad inusual"}'
        session.opener.open.side_effect = HTTPError(session.url, 403, "Forbidden", {"Server": "cloudflare"}, io.BytesIO(body))
        with self.assertRaises(demo.ApiError) as caught:
            session.login("admin-demo", "SECRET-PASSWORD")
        self.assertEqual(caught.exception.reason, "anomaly")

    def test_imported_web_token_validates_role_without_login_or_refresh(self):
        api = FakeApi()
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.accept_tokens(self.tokens())
        with patch("sys.stdin.isatty", return_value=True), patch("builtins.input") as prompt, \
                patch.object(demo.getpass, "getpass", return_value="Bearer WEB-ACCESS-TOKEN"), \
                patch.object(session, "login") as login, patch.object(session.opener, "open") as refresh, \
                redirect_stdout(io.StringIO()) as output:
            demo.prompt_token(api, session)
        self.assertEqual(api.calls, [("GET", "/api/v1/users/me", None)])
        self.assertEqual(api.token, "WEB-ACCESS-TOKEN")
        self.assertIsNone(api.session)
        self.assertIsNone(session.refresh_token)
        self.assertNotIn("WEB-ACCESS-TOKEN", output.getvalue())
        prompt.assert_not_called()
        login.assert_not_called()
        refresh.assert_not_called()

    def test_citizen_web_token_is_rejected_before_inventory_access(self):
        api = FakeApi()
        api.profile = {"type": "CITIZEN", "role": "USER"}
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        with patch("sys.stdin.isatty", return_value=True), \
                patch.object(demo.getpass, "getpass", return_value="CITIZEN-TOKEN"), redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(demo.DemoError, "ADMIN"):
                demo.prompt_token(api, session)
        self.assertIsNone(api.token)
        self.assertEqual(api.calls, [("GET", "/api/v1/users/me", None)])

    def test_expired_web_token_cannot_start_inventory_work(self):
        api = FakeApi()
        api.request = Mock(side_effect=demo.ApiError(401, "GET", "/api/v1/users/me"))
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        with patch("sys.stdin.isatty", return_value=True), \
                patch.object(demo.getpass, "getpass", return_value="EXPIRED-TOKEN"), redirect_stdout(io.StringIO()):
            with self.assertRaises(demo.ApiError):
                demo.prompt_token(api, session)
        self.assertIsNone(api.token)
        api.request.assert_called_once_with("GET", "/api/v1/users/me")

    def test_staff_login_contract_and_rotating_refresh_used_by_the_api(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.opener = Mock()
        session.opener.open.side_effect = [io.BytesIO(json.dumps(t).encode()) for t in
                                           (self.tokens(), self.tokens("access-2", "refresh-2"),
                                            self.tokens("access-3", "refresh-3"))]
        session.login("admin-demo", "PASSWORD-NOT-STORED")
        login = session.opener.open.call_args_list[0].args[0]
        self.assertEqual(login.full_url, demo.DEFAULT_AUTH_URL + "/auth/login")
        self.assertIsNone(login.get_header("Authorization"))
        self.assertEqual(login.get_header("User-agent"), demo.USER_AGENT)
        self.assertEqual(json.loads(login.data), {"username": "admin-demo", "password": "PASSWORD-NOT-STORED",
                                                 "clientId": demo.DEFAULT_CLIENT_ID})
        self.assertNotIn("password", vars(session))
        api = demo.Api("https://example.com", session=session)
        api.opener = Mock()
        api.opener.open.side_effect = [io.BytesIO(b"{}"), io.BytesIO(b"{}")]
        for access, refresh in (("access-2", "refresh-1"), ("access-3", "refresh-2")):
            session.expires_at = 0
            api.request("GET", "/api/v1/users/me")
            request = session.opener.open.call_args.args[0]
            self.assertEqual(request.full_url, demo.DEFAULT_AUTH_URL + "/auth/refresh")
            self.assertIsNone(request.get_header("Authorization"))
            self.assertEqual(json.loads(request.data), {"refreshToken": refresh})
            self.assertEqual(api.opener.open.call_args.args[0].get_header("Authorization"), "Bearer " + access)

    def test_live_token_reused_without_another_login_or_refresh(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.opener = Mock()
        session.accept_tokens(self.tokens())
        self.assertEqual(session.token(), "access-1")
        self.assertEqual(session.token(), "access-1")
        session.opener.open.assert_not_called()

    def test_unauthorized_write_is_not_retried_and_session_is_cleared(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.accept_tokens(self.tokens())
        api = demo.Api("https://example.com", session=session)
        api.opener = Mock()
        api.opener.open.side_effect = HTTPError("https://example.com", 401, "Unauthorized", {}, io.BytesIO())
        with self.assertRaises(demo.ApiError):
            api.request("PATCH", "/api/v1/bikes/900/station", {"stationId": 50})
        self.assertEqual(api.opener.open.call_count, 1)
        self.assertIsNone(session.access_token)
        self.assertIsNone(session.refresh_token)

    def test_failed_refresh_is_not_reused_or_exposed(self):
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        session.accept_tokens(self.tokens())
        session.expires_at = 0
        session.opener = Mock()
        session.opener.open.side_effect = HTTPError(session.url, 401, "Unauthorized", {}, io.BytesIO(b"refresh-1"))
        for _ in range(2):
            with self.assertRaises(demo.DemoError) as caught:
                session.token()
            self.assertNotIn("refresh-1", str(caught.exception))
        self.assertEqual(session.opener.open.call_count, 1)
        self.assertIsNone(session.access_token)
        self.assertIsNone(session.refresh_token)

    def test_citizen_or_non_admin_login_cannot_start_inventory_work(self):
        api = FakeApi()
        api.profile = {"type": "CITIZEN", "role": "USER"}
        session = demo.StaffSession(demo.DEFAULT_AUTH_URL, demo.DEFAULT_CLIENT_ID)
        with patch("sys.stdin.isatty", return_value=True), \
                patch("builtins.input", side_effect=["ciudadano", "TEST-PASSWORD"]), \
                patch.object(session, "login"), redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(demo.DemoError, "ADMIN"):
                demo.prompt_login(api, session)
        self.assertFalse(any(m != "GET" for m, _, _ in api.calls))
        self.assertIsNone(session.access_token)

    def test_wrong_backend_state_stops_before_prompting_or_sending_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.json"
            state.write_text(json.dumps({"version": 1, "url": "https://another.example"}))
            with patch("sys.stdin.isatty", return_value=True), patch("builtins.input") as prompts, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(demo.main(["--state-file", str(state)]), 1)
            prompts.assert_not_called()

    def test_non_interactive_menu_stops_without_prompting(self):
        with patch("sys.stdin.isatty", return_value=False), patch("builtins.input") as prompt, \
                redirect_stderr(io.StringIO()):
            self.assertEqual(demo.main([]), 1)
        prompt.assert_not_called()


if __name__ == "__main__":
    unittest.main()
