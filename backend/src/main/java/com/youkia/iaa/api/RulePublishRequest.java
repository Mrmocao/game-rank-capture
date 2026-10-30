package com.youkia.iaa.api;

import jakarta.validation.constraints.NotBlank;

public record RulePublishRequest(@NotBlank String rulesJson) {}
