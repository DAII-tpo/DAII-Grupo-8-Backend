package com.citypass.movilidad.mapper;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StationMapperTest {

    private final StationMapper mapper = Mappers.getMapper(StationMapper.class);

    @Test
    void toStationDTOMapeaEnumsYFechasComoTexto() {
        Instant creada = Instant.parse("2026-09-04T00:07:02Z");
        Station station = Station.builder()
                .id(1L)
                .name("Estación 9 de Julio")
                .address("Av. 9 de Julio 1030")
                .latitude(new BigDecimal("-34.6037"))
                .longitude(new BigDecimal("-58.3816"))
                .capacity(10)
                .status(StationStatus.ACTIVE)
                .source(StationSource.MANUAL)
                .createdAt(creada)
                .updatedAt(creada)
                .build();

        StationDTO dto = mapper.toStationDTO(station);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Estación 9 de Julio");
        assertThat(dto.getCapacity()).isEqualTo(10);
        assertThat(dto.getStatus()).isEqualTo("ACTIVE");
        assertThat(dto.getCreatedAt()).isEqualTo("2026-09-04T00:07:02Z");
        assertThat(dto.getUpdatedAt()).isEqualTo("2026-09-04T00:07:02Z");
        assertThat(dto.getDeletedAt()).isNull();
    }

    @Test
    void toStationDTOListMapeaTodosLosElementos() {
        Station station = Station.builder().id(7L).name("Estación Retiro").status(StationStatus.INACTIVE).build();

        List<StationDTO> dtos = mapper.toStationDTOList(List.of(station));

        assertThat(dtos).singleElement().satisfies(dto -> {
            assertThat(dto.getId()).isEqualTo(7L);
            assertThat(dto.getName()).isEqualTo("Estación Retiro");
            assertThat(dto.getStatus()).isEqualTo("INACTIVE");
        });
    }

    @Test
    void toStationMapeaElPedidoAlaEntidad() {
        StationRequestDTO request = StationRequestDTO.builder()
                .name("Estación Nueva")
                .address("Av. Corrientes 500")
                .latitude(new BigDecimal("-34.6000"))
                .longitude(new BigDecimal("-58.3800"))
                .capacity(25)
                .status(StationStatus.MAINTENANCE)
                .source(StationSource.MANUAL)
                .build();

        Station station = mapper.toStation(request);

        assertThat(station.getId()).isNull();
        assertThat(station.getName()).isEqualTo("Estación Nueva");
        assertThat(station.getAddress()).isEqualTo("Av. Corrientes 500");
        assertThat(station.getCapacity()).isEqualTo(25);
        assertThat(station.getStatus()).isEqualTo(StationStatus.MAINTENANCE);
        assertThat(station.getSource()).isEqualTo(StationSource.MANUAL);
    }

    @Test
    void devuelveNullSiLaEntradaEsNull() {
        assertThat(mapper.toStationDTO(null)).isNull();
        assertThat(mapper.toStation(null)).isNull();
        assertThat(mapper.toStationDTOList(null)).isNull();
    }
}
