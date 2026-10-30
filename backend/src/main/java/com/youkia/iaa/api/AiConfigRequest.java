package com.youkia.iaa.api;

import jakarta.validation.constraints.*;

public record AiConfigRequest(@NotBlank @Size(max=80) String providerName,
                              @NotBlank @Size(max=500) String baseUrl,
                              @NotBlank @Size(max=160) String model,
                              @Size(max=500) String apiKey,
                              @Min(5) @Max(300) Integer timeoutSeconds,
                              @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
                              Boolean enabled) {}
