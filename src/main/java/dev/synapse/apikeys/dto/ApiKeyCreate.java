package dev.synapse.apikeys.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Empty {@code scopes} ⇒ a snapshot of everything the creator can currently exercise. */
public record ApiKeyCreate(
    @NotNull @Size(min = 1, max = 200) String name,
    List<String> scopes,
    @Min(1) @Max(3650) Integer expiresInDays
) {
    public List<String> scopesOrEmpty() {
        return scopes == null ? List.of() : scopes;
    }
}
