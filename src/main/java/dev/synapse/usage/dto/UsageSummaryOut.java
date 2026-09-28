package dev.synapse.usage.dto;

import java.util.List;

public record UsageSummaryOut(String period, List<UsageCheckOut> metrics) {}
