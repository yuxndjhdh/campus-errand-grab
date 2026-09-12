package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.CompensationRequest;
import com.yu.errand.controller.dto.DisputeRequest;
import com.yu.errand.domain.model.Dispute;
import com.yu.errand.security.CurrentUserService;
import com.yu.errand.service.DisputeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {
    private final DisputeService disputes;
    private final CurrentUserService currentUser;

    public DisputeController(DisputeService disputes, CurrentUserService currentUser) {
        this.disputes = disputes;
        this.currentUser = currentUser;
    }

    @PostMapping("/payment/{paymentId}")
    public ApiResponse<Dispute> open(@PathVariable long paymentId, @Valid @RequestBody DisputeRequest request) {
        return ApiResponse.ok(disputes.open(paymentId, currentUser.id(), request.reason()));
    }

    @PostMapping("/{id}/compensate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Dispute> compensate(@PathVariable long id, @Valid @RequestBody CompensationRequest request) {
        return ApiResponse.ok(disputes.compensate(id, currentUser.id(), request));
    }
}
