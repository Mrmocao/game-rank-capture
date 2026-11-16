package com.youkia.iaa.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.collection.capture.SourceSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaptureLoginControllerTest {
 @TempDir Path root;
 @Test void viewerRequiresHttpsShortSessionAndActiveWindow()throws Exception{
  var file=root.resolve("status.json");var mapper=new ObjectMapper();
  var c=new CaptureLoginController(mock(SourceSessionStatus.class),mapper,"fixture-access-code-32-characters","https://capture.example.test",file.toString(),true);
  var r=new MockHttpServletRequest();r.setSecure(true);r.addHeader("Origin","https://capture.example.test");
  assertEquals(403,c.viewer(r).getStatusCode().value());
  var login=c.login(mapper.readTree("{\"code\":\"fixture-access-code-32-characters\"}"),r);
  String header=login.getHeaders().getFirst("Set-Cookie");assertTrue(header.contains("Secure"));assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Strict"));
  r.setCookies(new Cookie("capture_login",header.split("[=;]")[1]));
  assertEquals(403,c.viewer(r).getStatusCode().value());
  Files.writeString(file,"{\"state\":\"READY\",\"expiresAt\":\""+Instant.now().plusSeconds(300)+"\"}");
  assertEquals(204,c.viewer(r).getStatusCode().value());
  r.setSecure(false);assertEquals(403,c.viewer(r).getStatusCode().value());
  assertThrows(ApiException.class,()->c.login(mapper.createObjectNode(),r));
  r.setSecure(true);r.removeHeader("Origin");r.addHeader("Origin","https://other.example.test");assertThrows(ApiException.class,()->c.login(mapper.createObjectNode(),r));assertEquals(403,c.viewer(r).getStatusCode().value());
  Files.writeString(file,"{\"state\":\"READY\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}");assertEquals(403,c.viewer(r).getStatusCode().value());
 }
}
