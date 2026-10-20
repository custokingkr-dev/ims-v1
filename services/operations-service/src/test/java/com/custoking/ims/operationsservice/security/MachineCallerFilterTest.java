package com.custoking.ims.operationsservice.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
class MachineCallerFilterTest {
 @Test void sharedTokenAndForgedPrincipalCannotAuthorizeMachineRoute() throws Exception {
  var env=new MockEnvironment().withProperty("OUTBOX_RELAY_CALLER_SERVICE_ACCOUNTS","scheduler@project.iam.gserviceaccount.com");
  var filter=new MachineCallerFilter(env, token->Optional.of("gateway@project.iam.gserviceaccount.com"));
  var request=new MockHttpServletRequest("POST","/api/v1/internal/outbox/relay");
  request.addHeader("X-Authenticated-Role","SUPERADMIN");request.addHeader("X-Identity-Service-Token","shared");request.addHeader("Authorization","Bearer valid-for-wrong-account");
  var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
  filter.doFilter(request,response,chain);assertEquals(403,response.getStatus());assertNull(chain.getRequest());
 }
 @Test void unsignedServerlessHeaderRejectedButExactSignedCallerAccepted() throws Exception {
  var env=new MockEnvironment().withProperty("PASSWORD_RESET_DRAIN_CALLER_SERVICE_ACCOUNTS","scheduler@project.iam.gserviceaccount.com");
  var filter=new MachineCallerFilter(env, token->token.equals("signed") ? Optional.of("scheduler@project.iam.gserviceaccount.com"):Optional.empty());
  var request=new MockHttpServletRequest("POST","/api/v1/internal/password-reset/drain");request.addHeader("X-Serverless-Authorization","Bearer signature_removed");
  var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertEquals(403,response.getStatus());
  request=new MockHttpServletRequest("POST","/api/v1/internal/password-reset/drain");request.addHeader("Authorization","Bearer signed");
  response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);assertNotNull(chain.getRequest());
 }
 @Test void missingAllowlistDeniesEvenValidIdentity() throws Exception {
  var filter=new MachineCallerFilter(new MockEnvironment(),token->Optional.of("identity@project.iam.gserviceaccount.com"));
  var request=new MockHttpServletRequest("GET","/api/v1/internal/identity-directory/schools/1");request.addHeader("Authorization","Bearer signed");
  var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertEquals(403,response.getStatus());
 }
 @Test void deployedPrincipalHeadersNeedIndependentSignedGatewayCarrierEvenOnPublicRoutes() throws Exception {
  var env=new MockEnvironment().withProperty("USER_CONTEXT_CALLER_SERVICE_ACCOUNTS","gateway@project.iam.gserviceaccount.com");env.setActiveProfiles("dev");
  var filter=new MachineCallerFilter(env,token->token.equals("signed-gateway") ? Optional.of("gateway@project.iam.gserviceaccount.com"):Optional.of("backend@project.iam.gserviceaccount.com"));
  var request=new MockHttpServletRequest("GET","/api/v1/schools/1");request.addHeader("X-Authenticated-User-Id","1");request.addHeader("X-Authenticated-Role","SUPERADMIN");
  var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertEquals(403,response.getStatus());
  request=new MockHttpServletRequest("GET","/api/v1/schools/1");request.addHeader("X-Authenticated-User-Id","1");request.addHeader("X-IMS-Principal-Carrier-Token","Bearer signed-wrong-account");
  response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());assertEquals(403,response.getStatus());
  request=new MockHttpServletRequest("GET","/api/v1/schools/1");request.addHeader("X-Authenticated-User-Id","1");request.addHeader("X-IMS-Principal-Carrier-Token","Bearer signed-gateway");
  response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);assertNotNull(chain.getRequest());
 }

 @Test void encodedRoutesAndNoncanonicalDirectoryIdsCannotSkipCallerAuthentication() throws Exception {
  var filter=new MachineCallerFilter(new MockEnvironment(),token->Optional.empty());
  for(String path:java.util.List.of("/api/v1/internal/outbox/%72elay","/api/v1/internal/identity-directory/schools/+1","/api/v1/internal/identity-directory/schools/-1","/api/v1/%69nternal/identity-directory/schools/1","/api/v1/internal/unreviewed/capability")) {
   var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();var chain=new MockFilterChain();
   filter.doFilter(request,response,chain);assertEquals(403,response.getStatus(),path);assertNull(chain.getRequest(),path);
  }
 }

}
