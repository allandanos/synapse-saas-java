package dev.synapse.tenancy.dto;

import dev.synapse.core.validation.EmailAddress;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record MemberInvite(@NotNull @EmailAddress String email, List<String> roleKeys) {

    public List<String> roleKeysOrDefault() {
        return roleKeys == null ? List.of("member") : roleKeys;
    }
}
