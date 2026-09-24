package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.exception.station.StationNotFoundException;
import com.citypass.movilidad.mapper.StationMapper;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class StationService {

    private final StationRepository stationRepository;
    private final StationMapper stationMapper;
    private final UserRepository userRepository;

    public StationService(
            StationRepository stationRepository,
            StationMapper stationMapper,
            UserRepository userRepository) {
        this.stationRepository = stationRepository;
        this.stationMapper = stationMapper;
        this.userRepository = userRepository;
    }

    public StationDTO getStationById(Long id) {
        return stationMapper.toStationDTO(stationRepository.findById(id).orElseThrow(
                () -> new StationNotFoundException(id)
        ));
    }

    public List<StationDTO> getAllStations() {
        return stationMapper.toStationDTOList(stationRepository.findAll());
    }

    public StationDTO createStation(Long adminId, StationRequestDTO stationRequestDTO) {
        requireAdmin(adminId);
        Station station = stationMapper.toStation(stationRequestDTO);
        applyDefaults(station);
        return stationMapper.toStationDTO(stationRepository.save(station));
    }

    public StationDTO updateStation(Long adminId, Long id, StationRequestDTO stationRequestDTO) {
        requireAdmin(adminId);
        Station station = stationRepository.findById(id).orElseThrow(
                () -> new StationNotFoundException(id)
        );
        updateStationFields(stationRequestDTO, station);
        applyDefaults(station);
        return stationMapper.toStationDTO(stationRepository.save(station));

    }

    private User requireAdmin(Long userId) {
        if (userId == null) {
            throw new ForbiddenOperationException("Se requiere un usuario administrador para realizar esta operación");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() == null
                || !"ADMIN".equals(user.getRole().getName())) {
            throw new ForbiddenOperationException("El usuario no tiene permisos de administrador: " + userId);
        }
        return user;
    }

    /**
     * status y source son columnas NOT NULL pero opcionales en el request: se completan acá para
     * que omitirlas no termine en un error de integridad (MOV-021).
     */
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
