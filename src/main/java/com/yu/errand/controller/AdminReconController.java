package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.ReconReportView;
import com.yu.errand.domain.OrderStatus;
import com.yu.errand.repository.OrderRepository;
import com.yu.errand.repository.OutboxRepository;
import com.yu.errand.domain.model.OutboxEvent;
import com.yu.errand.service.GrabStats;
import com.yu.errand.service.ReconService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReconController {
    private final ReconService recon;
    private final GrabStats stats;
    private final OrderRepository orders;
    private final OutboxRepository outbox;

    public AdminReconController(ReconService recon, GrabStats stats, OrderRepository orders, OutboxRepository outbox) {
        this.recon = recon;
        this.stats = stats;
        this.orders = orders;
        this.outbox = outbox;
    }

    @PostMapping("/recon/run")
    public ApiResponse<ReconReportView> run() { return ApiResponse.ok(recon.run()); }

    @GetMapping("/recon/report")
    public ApiResponse<String> report() { return ApiResponse.ok(recon.latest()); }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grabAttempts", stats.attempts());
        result.put("grabWinners", stats.winners());
        result.put("grabRejected", stats.rejected());
        result.put("redisFiltered", stats.filtered());
        result.put("grabDbCas", stats.dbCas());
        result.put("rateLimited", stats.rateLimited());
        result.put("outboxPending", outbox.countByStatus("PENDING") + outbox.countByStatus("RETRY") + outbox.countByStatus("PROCESSING"));
        result.put("outboxDead", outbox.countByStatus("DEAD"));
        result.put("ordersPublished", orders.countByStatus(OrderStatus.PUBLISHED));
        result.put("ordersTaken", orders.countByStatus(OrderStatus.TAKEN));
        result.put("ordersSettled", orders.countByStatus(OrderStatus.SETTLED));
        result.put("ordersCancelled", orders.countByStatus(OrderStatus.CANCELLED));
        return ApiResponse.ok(result);
    }

    @GetMapping("/outbox/dead")
    public ApiResponse<java.util.List<OutboxEvent>> deadOutbox() {
        return ApiResponse.ok(outbox.dead(100));
    }

    @PostMapping("/outbox/{id}/replay")
    public ApiResponse<Void> replayOutbox(@PathVariable long id) {
        if (outbox.replay(id) != 1) {
            throw new com.yu.errand.common.BizException(com.yu.errand.common.ErrorCode.NOT_FOUND, "dead outbox event not found");
        }
        return ApiResponse.ok();
    }
}
