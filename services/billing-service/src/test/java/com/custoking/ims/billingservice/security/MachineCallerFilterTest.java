package com.custoking.ims.billingservice.security;
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

 // ---- notebook order tracker machine routes (spec 3.1) ----
 private static final java.util.Map<String,String> TRACKER_ROUTES=java.util.Map.of(
   "POST /api/v1/internal/notebook-trackers","school-core",
   "POST /api/v1/internal/notebook-trackers/CK-2026_0001/image-access","school-core",
   "GET /api/v1/internal/catalog-orders/CK-2026_0001/draft-images","operations",
   "PUT /api/v1/internal/catalog-orders/CK-2026_0001/tracker-status","operations",
   "GET /api/v1/internal/operator-directory/resolve","school-core",
   "GET /api/v1/internal/operator-directory/eligible","operations");
 private static MockEnvironment trackerAllowLists() {
  return new MockEnvironment().withProperty("NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS","school-core@project.iam.gserviceaccount.com")
    .withProperty("CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS","operations@project.iam.gserviceaccount.com")
    .withProperty("OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS","school-core@project.iam.gserviceaccount.com")
    .withProperty("OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS","operations@project.iam.gserviceaccount.com");
 }
 private static boolean admitted(MachineCallerFilter filter,String route,String token) throws Exception {
  String[] parts=route.split(" ",2);
  var request=new MockHttpServletRequest(parts[0],parts[1]);if(token!=null)request.addHeader("Authorization","Bearer "+token);
  var response=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,response,chain);
  if(chain.getRequest()==null)assertEquals(403,response.getStatus(),route);
  return chain.getRequest()!=null;
 }
 @Test void trackerRoutesAdmitOnlyTheirAllowListedSignedCaller() throws Exception {
  var filter=new MachineCallerFilter(trackerAllowLists(),token->Optional.of(token+"@project.iam.gserviceaccount.com"));
  for(var route:TRACKER_ROUTES.entrySet()) {
   String other=route.getValue().equals("school-core") ? "operations" : "school-core";
   assertTrue(admitted(filter,route.getKey(),route.getValue()),route.getKey());
   assertFalse(admitted(filter,route.getKey(),other),route.getKey());
   assertFalse(admitted(filter,route.getKey(),"api-gateway"),route.getKey());
   assertFalse(admitted(filter,route.getKey(),null),route.getKey());
  }
 }
 @Test void trackerRoutesWithoutTheirAllowListDenyEvenAValidSignedCaller() throws Exception {
  var filter=new MachineCallerFilter(new MockEnvironment(),token->Optional.of(token+"@project.iam.gserviceaccount.com"));
  for(var route:TRACKER_ROUTES.entrySet()) assertFalse(admitted(filter,route.getKey(),route.getValue()),route.getKey());
 }
 @Test void trackerOrderIdsAreTightlyMatched() throws Exception {
  var filter=new MachineCallerFilter(trackerAllowLists(),token->Optional.of(token+"@project.iam.gserviceaccount.com"));
  for(String route:java.util.List.of("POST /api/v1/internal/notebook-trackers/a.b/image-access","POST /api/v1/internal/notebook-trackers/a%2Eb/image-access",
    "POST /api/v1/internal/notebook-trackers/a/b/image-access","POST /api/v1/internal/notebook-trackers//image-access",
    "POST /api/v1/internal/notebook-trackers/"+"x".repeat(256)+"/image-access","POST /api/v1/internal/notebook-trackers/CK-1",
    "GET /api/v1/internal/catalog-orders/a.b/draft-images","PUT /api/v1/internal/catalog-orders/a%20b/tracker-status",
    "GET /api/v1/internal/operator-directory/resolve/extra","GET /api/v1/internal/operator-directory")) {
   assertFalse(admitted(filter,route,"school-core"),route);
   assertFalse(admitted(filter,route,"operations"),route);
  }
  assertTrue(admitted(filter,"POST /api/v1/internal/notebook-trackers/"+"x".repeat(255)+"/image-access","school-core"));
 }
 @Test void localTrustAdmitsRegisteredRoutesWithoutATokenOnlyOffCloudRun() throws Exception {
  var local=new MachineCallerFilter(new MockEnvironment().withProperty("MACHINE_CALLER_LOCAL_TRUST","true"),token->Optional.empty());
  for(String route:TRACKER_ROUTES.keySet()) assertTrue(admitted(local,route,null),route);
  assertTrue(admitted(local,"POST /api/v1/internal/outbox/relay",null));
  var cloudRun=new MachineCallerFilter(new MockEnvironment().withProperty("MACHINE_CALLER_LOCAL_TRUST","true").withProperty("K_SERVICE","x"),token->Optional.empty());
  for(String route:TRACKER_ROUTES.keySet()) assertFalse(admitted(cloudRun,route,null),route);
  for(String value:java.util.List.of("false","TRUE","yes","")) {
   var notTrue=new MachineCallerFilter(new MockEnvironment().withProperty("MACHINE_CALLER_LOCAL_TRUST",value),token->Optional.empty());
   assertFalse(admitted(notTrue,"POST /api/v1/internal/notebook-trackers",null),value);
  }
 }
 @Test void localTrustNeverOpensAnUnregisteredOrNoncanonicalInternalRoute() throws Exception {
  var local=new MachineCallerFilter(new MockEnvironment().withProperty("MACHINE_CALLER_LOCAL_TRUST","true"),token->Optional.empty());
  for(String route:java.util.List.of("GET /api/v1/internal/unreviewed/capability","POST /api/v1/internal/notebook-trackers/a.b/image-access",
    "POST /api/v1/internal/notebook-trackers/a%2Eb/image-access","GET /api/v1/internal/operator-directory/resolve/extra","GET /api/v1/internal/identity-directory/schools/+1"))
   assertFalse(admitted(local,route,null),route);
 }

}
