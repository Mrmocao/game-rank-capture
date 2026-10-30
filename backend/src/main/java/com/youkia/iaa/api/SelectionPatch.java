package com.youkia.iaa.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SelectionPatch(@Size(max=40) String owner, Long referenceVersionId, String status,
                             LocalDate selectedOn, LocalDate startedOn, LocalDate finishedOn,
                             @Size(max=1000) String note, @NotNull Integer version) {}
