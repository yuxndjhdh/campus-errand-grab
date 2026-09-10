package com.yu.errand.job;

import com.yu.errand.service.ReconService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReconJob {
    private static final Logger log = LoggerFactory.getLogger(ReconJob.class);
    private final ReconService recon;

    public ReconJob(ReconService recon) { this.recon = recon; }

    @Scheduled(fixedDelayString = "${app.recon.interval-ms:60000}", initialDelay = 10000)
    public void run() {
        try { recon.run(); } catch (RuntimeException ex) { log.error("scheduled reconciliation failed", ex); }
    }
}
