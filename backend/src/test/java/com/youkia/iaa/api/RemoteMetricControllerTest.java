package com.youkia.iaa.api;
import com.youkia.iaa.collection.capture.GameMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RemoteMetricControllerTest {
 @Test void missingWrongAndDisabledTokensCannotClaimOrSubmit(){var service=mock(GameMetricService.class);var controller=new RemoteMetricController(service,"a","secret-fixture");assertThrows(ResponseStatusException.class,()->controller.claim(null));assertThrows(ResponseStatusException.class,()->controller.claim("wrong"));assertThrows(ResponseStatusException.class,()->controller.result("wrong",null));assertThrows(ResponseStatusException.class,()->new RemoteMetricController(service,"a","").claim(""));verifyNoInteractions(service);}
}
