package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.BikeCreateRequest;
import com.citypass.movilidad.dto.BikeResponse;
import com.citypass.movilidad.dto.BikeStatusChangeRequest;
import com.citypass.movilidad.dto.BikeTransferRequest;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.service.BikeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BikeControllerTest {

    private final BikeService service = mock(BikeService.class);
    private final BikeController controller = new BikeController(service);

    @Test
    void delegatesAllBikeOperations() {
        BikeResponse bike = new BikeResponse(1L, "BIKE-1", 10L, "Centro", BikeStatus.AVAILABLE,
                null, null, null, null, null);
        BikeCreateRequest create = new BikeCreateRequest("BIKE-1", 10L, BikeStatus.AVAILABLE, null, null);
        BikeStatusChangeRequest status = new BikeStatusChangeRequest(BikeStatus.MAINTENANCE, "service");
        BikeTransferRequest transfer = new BikeTransferRequest(20L);
        when(service.create(create)).thenReturn(bike);
        when(service.findById(1L)).thenReturn(bike);
        when(service.findByStation(10L)).thenReturn(List.of(bike));
        when(service.findAvailable(10L)).thenReturn(List.of(bike));
        when(service.changeStatus(1L, status)).thenReturn(bike);
        when(service.transfer(1L, 20L)).thenReturn(bike);
        when(service.history(1L)).thenReturn(List.of());

        assertThat(controller.create(create).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.findById(1L)).isEqualTo(bike);
        assertThat(controller.findByStation(10L)).containsExactly(bike);
        assertThat(controller.findAvailable(10L)).containsExactly(bike);
        assertThat(controller.changeStatus(1L, status)).isEqualTo(bike);
        assertThat(controller.transfer(1L, transfer)).isEqualTo(bike);
        assertThat(controller.history(1L)).isEmpty();
        assertThat(controller.delete(1L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(1L);
    }
}
