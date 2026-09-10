package com.yu.errand.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.controller.dto.ReconReportView;
import com.yu.errand.repository.ReconRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReconService {
    private final ReconRepository recon;
    private final ObjectMapper objectMapper;
    private final Counter failureCounter;
    private final AtomicLong lastSuccessTimestamp = new AtomicLong(0);

    public ReconService(ReconRepository recon, ObjectMapper objectMapper, MeterRegistry registry) {
        this.recon = recon;
        this.objectMapper = objectMapper;
        this.failureCounter = Counter.builder("recon_failure_total").register(registry);
        io.micrometer.core.instrument.Gauge.builder("recon_last_success_timestamp", lastSuccessTimestamp, AtomicLong::get)
                .register(registry);
    }

    @Transactional
    public ReconReportView run() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("INV-1-ledger-sum-zero", recon.ledgerTotal() == 0L);
        checks.put("INV-2-materialized-balances-match-ledger", recon.accountLedgerMismatches() == 0L);
        checks.put("INV-3-escrow-matches-open-orders", recon.escrowMismatch() == 0L);
        checks.put("INV-4-only-mint-may-be-negative", recon.illegalNegativeAccounts() == 0L);
        checks.put("INV-5-no-overdue-open-order", recon.overdueOpenOrders() == 0L);
        boolean passed = checks.values().stream().allMatch(Boolean.TRUE::equals);
        if (!passed) failureCounter.increment();
        else lastSuccessTimestamp.set(System.currentTimeMillis() / 1000);
        String runAt = Instant.now().toString();
        try {
            recon.saveReport(passed, objectMapper.writeValueAsString(Map.of("passed", passed, "invariants", checks, "runAt", runAt)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        return new ReconReportView(passed, checks, runAt);
    }

    @Transactional(readOnly = true)
    public String latest() { return recon.latestReport(); }
}
