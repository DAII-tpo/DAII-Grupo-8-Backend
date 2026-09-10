package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.service.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
@Tag(name = "Estaciones")
public class StationController {

    private final StationService stationService;

    @GetMapping()
    @Operation(summary = "Obtiene todas las estaciones")
    public List<StationDTO> getStationById() {
        return stationService.getAllStations();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene la estación por ID")
    public StationDTO getStationById(@PathVariable Long id) {
        return stationService.getStationById(id);
    }

    @PostMapping()
    @Operation(summary = "Crea una nueva estación")
    public StationDTO createStation(@RequestBody StationRequestDTO stationRequestDTO) {
        return stationService.createStation(stationRequestDTO);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Actualiza una estación existente")
    public StationDTO updateStation(@PathVariable Long id, @RequestBody StationRequestDTO stationRequestDTO) {
        return stationService.updateStation(id, stationRequestDTO);
    }

}
