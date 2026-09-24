package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.exception.station.StationNotFoundException;
import com.citypass.movilidad.mapper.StationMapper;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.StationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** Alta, consulta y edición de estaciones. */
@Service
@Slf4j
public class StationService {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;

    public StationService(
            StationRepository stationRepository,
            StationMapper stationMapper) {
        this.stationRepository = stationRepository;
        this.stationMapper = stationMapper;
    }

    public StationDTO getStationById(Long id) {
        return stationMapper.toStationDTO(stationRepository.findById(id).orElseThrow(
                () -> new StationNotFoundException(id)
        ));
    }

    public List<StationDTO> getAllStations() {
        return stationMapper.toStationDTOList(stationRepository.findAll());
    }

    public StationDTO createStation(StationRequestDTO stationRequestDTO) {
        Station station = stationMapper.toStation(stationRequestDTO);
        applyDefaults(station);
        return stationMapper.toStationDTO(stationRepository.save(station));
    }

    public StationDTO updateStation(Long id, StationRequestDTO stationRequestDTO) {

        Station station = stationRepository.findById(id).orElseThrow(
                () -> new StationNotFoundException(id)
        );
        updateStationFields(stationRequestDTO, station);
        applyDefaults(station);
        return stationMapper.toStationDTO(stationRepository.save(station));

    }

    // status y source son opcionales en el request pero obligatorios en la base.
    private static void applyDefaults(Station station) {
        if (station.getStatus() == null) {
            station.setStatus(StationStatus.ACTIVE);
        }
        if (station.getSource() == null) {
            station.setSource(StationSource.MANUAL);
        }
    }

    private static void updateStationFields(StationRequestDTO stationRequestDTO, Station station) {
        station.setName(stationRequestDTO.getName());
        station.setAddress(stationRequestDTO.getAddress());
        station.setLatitude(stationRequestDTO.getLatitude());
        station.setLongitude(stationRequestDTO.getLongitude());
        station.setCapacity(stationRequestDTO.getCapacity());
        station.setStatus(stationRequestDTO.getStatus());
    }

}
