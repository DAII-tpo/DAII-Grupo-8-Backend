package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.citypass.movilidad.service.RestockTransferPlanner.*;
import static org.assertj.core.api.Assertions.assertThat;

class RestockTransferPlannerTest {
    @Test
    void expandsCoverageOnlyAtTheConfiguredThresholds() {
        assertThat(FestivalRestockService.coverageMeters(1000)).isEqualTo(300);
        assertThat(FestivalRestockService.coverageMeters(2499)).isEqualTo(300);
        assertThat(FestivalRestockService.coverageMeters(2500)).isEqualTo(500);
        assertThat(FestivalRestockService.coverageMeters(4999)).isEqualTo(500);
        assertThat(FestivalRestockService.coverageMeters(5000)).isEqualTo(750);
        assertThat(FestivalRestockService.coverageMeters(50000)).isEqualTo(750);
    }

    @Test
    void sharesTwoBikesAcrossThreeDestinationsWithoutReusingTheSourceStock() {
        var destinations = List.of(
                new Destination(1, 18, List.of(new Route(4, 649))),
                new Destination(2, 15, List.of(new Route(4, 525))),
                new Destination(3, 6, List.of(new Route(4, 533))));

        assertThat(allocate(destinations, Map.of(4L, 2))).containsExactly(
                new Transfer(1, new Route(4, 649), 1),
                new Transfer(2, new Route(4, 525), 1));
    }

    @Test
    void usesAnotherSourceAndRespectsEachDestinationLimit() {
        var nearby = new Route(3, 100);
        var farther = new Route(4, 200);
        var destinations = List.of(new Destination(1, 1, List.of(nearby, farther)),
                new Destination(2, 3, List.of(nearby, farther)));

        assertThat(allocate(destinations, Map.of(3L, 2, 4L, 4))).containsExactly(
                new Transfer(1, nearby, 1), new Transfer(2, nearby, 1), new Transfer(2, farther, 2));
    }
}
