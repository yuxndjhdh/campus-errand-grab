package com.yu.errand;

import com.yu.errand.service.ReconService;
import com.yu.errand.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ReconDetectsDriftIT extends IntegrationTestBase {
    @Autowired private ReconService recon;

    @Test
    void deliberatelyDriftedBalanceFailsRecon() {
        long userId = user("drift-user");
        jdbc.update("UPDATE t_account SET balance_cents=123 WHERE user_id=? AND account_type='AVAILABLE'", userId);
        assertFalse(recon.run().passed());
    }
}

