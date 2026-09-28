package dev.synapse.tenancy.dto;

import jakarta.validation.constraints.Pattern;
import java.util.List;

public record MemberUpdate(List<String> roleKeys, @Pattern(regexp = "^(active|suspended)$") String status) {}
