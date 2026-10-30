package com.youkia.iaa.api;

import java.util.UUID;

public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> of(T data) { return new ApiResponse<>(data, UUID.randomUUID().toString()); }
}
