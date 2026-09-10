package com.yu.errand.controller.dto;

import java.util.Map;

public record ReconReportView(boolean passed, Map<String, Object> invariants, String runAt) {}

