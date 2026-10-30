package com.youkia.iaa.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SelectionPatch(@Size(max=40) String owner, Long referenceVersionId, String status,
                             LocalDate selectedOn, LocalDate startedOn, LocalDate finishedOn,
                             @Size(max=1000) String note, @NotNull Integer version, Long referenceListingId) {
    public SelectionPatch(String owner,Long referenceVersionId,String status,LocalDate selectedOn,LocalDate startedOn,LocalDate finishedOn,String note,Integer version){this(owner,referenceVersionId,status,selectedOn,startedOn,finishedOn,note,version,null);}
}
