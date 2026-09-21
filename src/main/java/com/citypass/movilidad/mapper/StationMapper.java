package com.citypass.movilidad.mapper;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.model.Station;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface StationMapper {

    StationDTO toStationDTO(Station station);

    List<StationDTO> toStationDTOList(List<Station> stations);

    Station toStation(StationRequestDTO stationRequestDTO);

}
