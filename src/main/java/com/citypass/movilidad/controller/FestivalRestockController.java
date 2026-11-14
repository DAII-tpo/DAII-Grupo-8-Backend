package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.service.FestivalRestockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    public record TargetRequest(@NotNull @Positive Integer targetBikes) {}

    @PatchMapping("/{id}/target")
    public FestivalRestockTaskResponse updateTarget(@PathVariable long id, @Valid @RequestBody TargetRequest request) {
        return restock.updateTarget(id, request.targetBikes());
    }

    @PatchMapping("/{id}/complete")
    public FestivalRestockTaskResponse complete(@PathVariable long id) {
        return restock.completeManually(id);
    }

    @GetMapping("/transfer-plan")
    public List<FestivalRestockService.EventTransferPlan> transferPlans(
            @RequestParam(defaultValue = "false") boolean useModel) {
        return restock.transferPlans(useModel);
    }

    @GetMapping("/{id}/suggestion")
    public List<FestivalRestockService.TransferSuggestion> suggestTransfer(@PathVariable long id) {
        return restock.suggestTransfer(id);
    }
}
