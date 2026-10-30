package com.youkia.iaa.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SelectionRequest(@NotBlank @Size(max=40) String owner, Long referenceVersionId, String status,
                               LocalDate selectedOn, LocalDate startedOn, LocalDate finishedOn, @Size(max=1000) String note, Long referenceListingId) {
    public SelectionRequest(String owner,Long referenceVersionId,String status,LocalDate selectedOn,LocalDate startedOn,LocalDate finishedOn,String note){this(owner,referenceVersionId,status,selectedOn,startedOn,finishedOn,note,null);}
}
