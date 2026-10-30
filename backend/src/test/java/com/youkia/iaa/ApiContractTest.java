package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.SelectionRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    @Test void selectionRequestSerializesStableFieldNames() throws Exception {
        var request = new SelectionRequest("叶工", 2001L, "SELECTED", null, null, null, "DEMO");
        var json = new ObjectMapper().writeValueAsString(request);
        assertTrue(json.contains("\"owner\":\"叶工\"")); assertTrue(json.contains("\"referenceVersionId\":2001"));
    }
    @Test void ownerIsRequiredAndLengthBounded() {
        var validator=Validation.buildDefaultValidatorFactory().getValidator();
        assertFalse(validator.validate(new SelectionRequest("",null,null,null,null,null,null)).isEmpty());
        assertFalse(validator.validate(new SelectionRequest("a".repeat(41),null,null,null,null,null,null)).isEmpty());
    }
}
