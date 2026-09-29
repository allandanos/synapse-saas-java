package dev.synapse.storage.dto;

public record PresignResponse(String url, String key, int expiresIn) {}
