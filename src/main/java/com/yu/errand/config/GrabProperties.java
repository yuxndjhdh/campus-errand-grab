package com.yu.errand.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app")
public class GrabProperties {
    private final Grab grab = new Grab();
    private final Order order = new Order();
    private final Commission commission = new Commission();
    private final Recon recon = new Recon();
    private final Reliability reliability = new Reliability();
    private final Security security = new Security();
    private final Admin admin = new Admin();
    private final Payment payment = new Payment();

    public Grab getGrab() { return grab; }
    public Order getOrder() { return order; }
    public Commission getCommission() { return commission; }
    public Recon getRecon() { return recon; }
    public Reliability getReliability() { return reliability; }
    public Security getSecurity() { return security; }
    public Admin getAdmin() { return admin; }
    public Payment getPayment() { return payment; }

    public static class Grab {
        private boolean redisPrefilterEnabled = true;
        private long markerTtlSlackSeconds = 30;
        private boolean rateLimitEnabled = true;
        private int rateLimitPerUser = 30;
        private int rateLimitPerOrder = 200;
        private int rateLimitWindowSeconds = 10;
        public boolean isRedisPrefilterEnabled() { return redisPrefilterEnabled; }
        public void setRedisPrefilterEnabled(boolean value) { redisPrefilterEnabled = value; }
        public long getMarkerTtlSlackSeconds() { return markerTtlSlackSeconds; }
        public void setMarkerTtlSlackSeconds(long value) { markerTtlSlackSeconds = value; }
        public boolean isRateLimitEnabled() { return rateLimitEnabled; }
        public void setRateLimitEnabled(boolean value) { rateLimitEnabled = value; }
        public int getRateLimitPerUser() { return rateLimitPerUser; }
        public void setRateLimitPerUser(int value) { rateLimitPerUser = value; }
        public int getRateLimitPerOrder() { return rateLimitPerOrder; }
        public void setRateLimitPerOrder(int value) { rateLimitPerOrder = value; }
        public int getRateLimitWindowSeconds() { return rateLimitWindowSeconds; }
        public void setRateLimitWindowSeconds(int value) { rateLimitWindowSeconds = value; }
    }

    public static class Order {
        private long claimTtlSeconds = 300;
        private long deliverTtlSeconds = 3600;
        public long getClaimTtlSeconds() { return claimTtlSeconds; }
        public void setClaimTtlSeconds(long value) { claimTtlSeconds = value; }
        public long getDeliverTtlSeconds() { return deliverTtlSeconds; }
        public void setDeliverTtlSeconds(long value) { deliverTtlSeconds = value; }
    }

    public static class Commission {
        private BigDecimal rate = new BigDecimal("0.10");
        private long platformUserId = 2;
        public BigDecimal getRate() { return rate; }
        public void setRate(BigDecimal value) { rate = value; }
        public long getPlatformUserId() { return platformUserId; }
        public void setPlatformUserId(long value) { platformUserId = value; }
    }

    public static class Recon {
        private long intervalMs = 60000;
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long value) { intervalMs = value; }
    }

    public static class Reliability {
        private int settlementMaxRetries = 12;
        private long settlementBaseBackoffSeconds = 1;
        public int getSettlementMaxRetries() { return settlementMaxRetries; }
        public void setSettlementMaxRetries(int value) { settlementMaxRetries = value; }
        public long getSettlementBaseBackoffSeconds() { return settlementBaseBackoffSeconds; }
        public void setSettlementBaseBackoffSeconds(long value) { settlementBaseBackoffSeconds = value; }
    }

    public static class Security {
        private String jwtSecret = "local-development-secret-change-me";
        private String previousJwtSecret = "";
        private long accessTokenTtlSeconds = 900;
        private boolean requireRedisPassword = false;
        private int loginFailureLimit = 5;
        private int loginIpFailureLimit = 30;
        private int loginWindowSeconds = 60;
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String value) { jwtSecret = value; }
        public String getPreviousJwtSecret() { return previousJwtSecret; }
        public void setPreviousJwtSecret(String value) { previousJwtSecret = value; }
        public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
        public void setAccessTokenTtlSeconds(long value) { accessTokenTtlSeconds = value; }
        public boolean isRequireRedisPassword() { return requireRedisPassword; }
        public void setRequireRedisPassword(boolean value) { requireRedisPassword = value; }
        public int getLoginFailureLimit() { return loginFailureLimit; }
        public void setLoginFailureLimit(int value) { loginFailureLimit = value; }
        public int getLoginIpFailureLimit() { return loginIpFailureLimit; }
        public void setLoginIpFailureLimit(int value) { loginIpFailureLimit = value; }
        public int getLoginWindowSeconds() { return loginWindowSeconds; }
        public void setLoginWindowSeconds(int value) { loginWindowSeconds = value; }
    }

    public static class Admin {
        private String username = "";
        private String password = "";
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
    }

    public static class Payment {
        private boolean enabled = true;
        private String webhookSecret = "local-payment-webhook-secret";
        private long webhookSkewSeconds = 300;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String value) { webhookSecret = value; }
        public long getWebhookSkewSeconds() { return webhookSkewSeconds; }
        public void setWebhookSkewSeconds(long value) { webhookSkewSeconds = value; }
    }
}
