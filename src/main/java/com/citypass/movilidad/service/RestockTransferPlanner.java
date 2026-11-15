package com.citypass.movilidad.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RestockTransferPlanner {
    record Route(long sourceId, int distanceMeters) {}
    record Destination(long stationId, int limit, List<Route> routes) {}
    record Transfer(long destinationId, Route route, int bikes) {}

    private RestockTransferPlanner() {}

    static List<Transfer> allocate(List<Destination> destinations, Map<Long, Integer> sourceBudgets) {
        var remaining = new HashMap<>(sourceBudgets);
        var assigned = new HashMap<Long, Integer>();
        var transfers = new ArrayList<Transfer>();
        var byDestination = new LinkedHashMap<Long, Map<Route, Integer>>();

        // Cada destino recibe hasta una bici por vuelta, en orden de prioridad.
        boolean allocated;
        do {
            allocated = false;
            for (var destination : destinations) {
                if (assigned.getOrDefault(destination.stationId(), 0) >= destination.limit()) continue;
                for (var route : destination.routes()) {
                    if (remaining.getOrDefault(route.sourceId(), 0) <= 0) continue;
                    remaining.compute(route.sourceId(), (id, count) -> count - 1);
                    assigned.merge(destination.stationId(), 1, Integer::sum);
                    byDestination.computeIfAbsent(destination.stationId(), id -> new LinkedHashMap<>())
                            .merge(route, 1, Integer::sum);
                    allocated = true;
                    break;
                }
            }
        } while (allocated);

        byDestination.forEach((destinationId, routes) -> routes.forEach((route, bikes) ->
                transfers.add(new Transfer(destinationId, route, bikes))));
        return transfers;
    }
}
