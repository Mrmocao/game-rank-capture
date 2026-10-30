package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.config.WorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAccessTest {
    @Test void unauthorizedApiIsBlockedAndBearerWorks()throws Exception{
        var access=new WorkspaceAccess("SYNTHETIC-access-token","org-a",new ObjectMapper());
        var request=new MockHttpServletRequest("GET","/api/v1/profile/definitions");var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        access.doFilter(request,response,(q,r)->called.set(true));assertEquals(401,response.getStatus());assertFalse(called.get());
        request=new MockHttpServletRequest("GET","/api/v1/profile/definitions");request.addHeader("Authorization","Bearer SYNTHETIC-access-token");response=new MockHttpServletResponse();access.doFilter(request,response,(q,r)->called.set(true));assertTrue(called.get());
    }
    @Test void loginCookieAndTenantCheckAreServerOwned()throws Exception{
        var access=new WorkspaceAccess("SYNTHETIC-access-token","org-a",new ObjectMapper());assertNull(access.login("wrong","test"));String session=access.login("SYNTHETIC-access-token","test");assertNotNull(session);
        var request=new MockHttpServletRequest("GET","/api/v1/profile/definitions");request.setCookies(new Cookie("iaa_session",session));assertTrue(access.authenticated(request));request.addHeader("X-Tenant-Id","org-b");var response=new MockHttpServletResponse();access.doFilter(request,response,(q,r)->fail("tenant mismatch must not reach controller"));assertEquals(403,response.getStatus());
        request=new MockHttpServletRequest("GET","/api/v1/profile/definitions");request.setCookies(new Cookie("iaa_session","forged"));assertFalse(access.authenticated(request));
    }
    @Test void blankTokenAllowsDevelopmentAndLoginIsRateLimited(){
        assertTrue(new WorkspaceAccess("","org-a",new ObjectMapper()).authenticated(new MockHttpServletRequest()));
        var access=new WorkspaceAccess("SYNTHETIC-access-token","org-a",new ObjectMapper());for(int i=0;i<10;i++)assertNull(access.login("wrong","test"));assertThrows(IllegalArgumentException.class,()->access.login("SYNTHETIC-access-token","test"));
    }
}
