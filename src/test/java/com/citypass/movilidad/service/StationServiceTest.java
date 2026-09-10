package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.exception.station.StationNotFoundException;
import com.citypass.movilidad.mapper.StationMapper;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationServiceTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private StationMapper stationMapper;

    @InjectMocks
    private StationService stationService;

    private static Station estacion(Long id, String nombre) {
        return Station.builder()
                .id(id)
                .name(nombre)
                .address("Av. 9 de Julio 1030")
                .latitude(new BigDecimal("-34.6037"))
                .longitude(new BigDecimal("-58.3816"))
                .capacity(10)
                .status(StationStatus.ACTIVE)
                .source(StationSource.MANUAL)
                .build();
    }

    private static StationRequestDTO pedido(String nombre) {
        return StationRequestDTO.builder()
                .name(nombre)
                .address("Av. Corrientes 500")
                .latitude(new BigDecimal("-34.6000"))
                .longitude(new BigDecimal("-58.3800"))
                .capacity(25)
                .status(StationStatus.MAINTENANCE)
                .source(StationSource.MANUAL)
                .build();
    }

    private static StationDTO respuesta(Long id, String nombre) {
        return new StationDTO(id, nombre, "Av. 9 de Julio 1030", new BigDecimal("-34.6037"),
                new BigDecimal("-58.3816"), 10, "ACTIVE", null, null, null);
    }

    @Test
    void getStationByIdDevuelveLaEstacionMapeada() {
        Station station = estacion(1L, "Estación 9 de Julio");
        StationDTO esperado = respuesta(1L, "Estación 9 de Julio");
        when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
        when(stationMapper.toStationDTO(station)).thenReturn(esperado);

        StationDTO resultado = stationService.getStationById(1L);

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void getStationByIdLanzaExcepcionSiLaEstacionNoExiste() {
        when(stationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stationService.getStationById(99L))
                .isInstanceOf(StationNotFoundException.class)
                .hasMessage("La estación con ID 99 no existe");
    }

    @Test
    void getAllStationsDevuelveTodasLasEstacionesMapeadas() {
        List<Station> stations = List.of(estacion(1L, "Estación 9 de Julio"), estacion(2L, "Estación Retiro"));
        List<StationDTO> esperado = List.of(respuesta(1L, "Estación 9 de Julio"), respuesta(2L, "Estación Retiro"));
        when(stationRepository.findAll()).thenReturn(stations);
        when(stationMapper.toStationDTOList(stations)).thenReturn(esperado);

        List<StationDTO> resultado = stationService.getAllStations();

        assertThat(resultado).containsExactlyElementsOf(esperado);
    }

    @Test
    void createStationPersisteLaEstacionMapeadaDesdeElPedido() {
        StationRequestDTO request = pedido("Estación Nueva");
        Station aPersistir = estacion(null, "Estación Nueva");
        Station persistida = estacion(5L, "Estación Nueva");
        StationDTO esperado = respuesta(5L, "Estación Nueva");
        when(stationMapper.toStation(request)).thenReturn(aPersistir);
        when(stationRepository.save(aPersistir)).thenReturn(persistida);
        when(stationMapper.toStationDTO(persistida)).thenReturn(esperado);

        StationDTO resultado = stationService.createStation(request);

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void updateStationActualizaLosCamposYPersisteLaEstacion() {
        Station existente = estacion(1L, "Estación Vieja");
        StationRequestDTO request = pedido("Estación Renombrada");
        StationDTO esperado = respuesta(1L, "Estación Renombrada");
        when(stationRepository.findById(1L)).thenReturn(Optional.of(existente));
        when(stationRepository.save(existente)).thenReturn(existente);
        when(stationMapper.toStationDTO(existente)).thenReturn(esperado);

        StationDTO resultado = stationService.updateStation(1L, request);

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationRepository).save(captor.capture());
        Station guardada = captor.getValue();
        assertThat(guardada.getId()).isEqualTo(1L);
        assertThat(guardada.getName()).isEqualTo("Estación Renombrada");
        assertThat(guardada.getAddress()).isEqualTo("Av. Corrientes 500");
        assertThat(guardada.getLatitude()).isEqualByComparingTo("-34.6000");
        assertThat(guardada.getLongitude()).isEqualByComparingTo("-58.3800");
        assertThat(guardada.getCapacity()).isEqualTo(25);
        assertThat(guardada.getStatus()).isEqualTo(StationStatus.MAINTENANCE);
        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void updateStationLanzaExcepcionSiLaEstacionNoExiste() {
        when(stationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stationService.updateStation(99L, pedido("Estación Fantasma")))
                .isInstanceOf(StationNotFoundException.class)
                .hasMessage("La estación con ID 99 no existe");

        verify(stationRepository, never()).save(any(Station.class));
    }
}
