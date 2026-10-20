package com.custoking.ims.identityservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CookieRequestProvenanceFilterTest {
    @Test void hostileNullAndAbsentOriginCannotChangeState() throws Exception {
        for(String origin:new String[]{null,"null","https://phishing.test","https://ims.test.evil.test"}) {
            var req=new MockHttpServletRequest("POST","/api/v1/auth/refresh");if(origin!=null)req.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();var mutated=new AtomicBoolean();
            new CookieRequestProvenanceFilter("https://ims.test").doFilter(req,response,(a,b)->mutated.set(true));
            assertEquals(403,response.getStatus());assertFalse(mutated.get());
        }
    }
    @Test void allowlistedOriginOrSameOriginRefererIsAccepted() throws Exception {
        for(boolean explicit:new boolean[]{true,false}) {
            var req=new MockHttpServletRequest("POST","/api/v1/auth/logout");
            if(explicit)req.addHeader("Origin","https://ims.test");
            else { req.addHeader("Sec-Fetch-Site","same-origin");req.addHeader("Referer","https://ims.test/settings"); }
            var mutated=new AtomicBoolean();var response=new MockHttpServletResponse();
            new CookieRequestProvenanceFilter("https://ims.test").doFilter(req,response,(a,b)->mutated.set(true));
            assertTrue(mutated.get());
        }
    }
}
