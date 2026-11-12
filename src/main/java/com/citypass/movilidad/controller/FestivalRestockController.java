package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.service.FestivalRestockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/festival-restock-tasks")
public class FestivalRestockController {
    private final FestivalRestockService restock;

    public FestivalRestockController(FestivalRestockService restock) {
        this.restock = restock;
    }

    @GetMapping
    public List<FestivalRestockTaskResponse> findUpcoming() {
        return restock.findUpcoming();
    }
}
