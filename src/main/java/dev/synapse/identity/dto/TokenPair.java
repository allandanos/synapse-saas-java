package dev.synapse.identity.dto;

public record TokenPair(String accessToken, String refreshToken, String tokenType, int expiresIn) {

    public static TokenPair bearer(String accessToken, String refreshToken, int expiresIn) {
        return new TokenPair(accessToken, refreshToken, "bearer", expiresIn);
    }
}
