package com.custoking.ims.platformservice.infrastructure;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SchoolCoreBroadcastRecipientPolicyTest {
 private HttpServer server;
 private java.util.concurrent.ExecutorService executor;
 private SchoolCoreBroadcastRecipientPolicy client;
 @BeforeEach void setup() throws Exception {
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.start();
  client=new SchoolCoreBroadcastRecipientPolicy(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort(),"peer");
 }
 @AfterEach void close() { server.stop(0);executor.shutdownNow(); }
 @Test void slowDribblingResponseHasCompleteExchangeDeadline() {
  server.createContext("/api/v1/internal/notifications/broadcast-recipients",exchange->{
   exchange.sendResponseHeaders(200,0);
   try(var stream=exchange.getResponseBody()) { for(int i=0;i<70;i++) { stream.write(' ');stream.flush();Thread.sleep(100); } }
   catch(Exception cancelled) { }
  });
  long started=System.nanoTime();
  assertThatThrownBy(()->client.resolve(1,UUID.randomUUID(),List.of("SMS"),List.of(1L))).hasMessage("Recipient policy unavailable");
  assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(7));
 }
 @Test void oversizedResponseIsRejectedBeforeDeserializing() {
  server.createContext("/api/v1/internal/notifications/broadcast-recipients",exchange->{
   byte[] body=new byte[3*1024*1024];Arrays.fill(body,(byte)' ');exchange.sendResponseHeaders(200,body.length);
   try(var stream=exchange.getResponseBody()) { stream.write(body); }catch(Exception cancelled) { }
  });
  assertThatThrownBy(()->client.resolve(1,UUID.randomUUID(),List.of("SMS"),List.of(1L))).hasMessage("Recipient policy unavailable");
 }
 @Test void exactScopedDenialResponseIsAccepted() {
  UUID id=UUID.randomUUID();String body="[{\"schoolId\":1,\"studentId\":1,\"channel\":\"SMS\",\"eventId\":\"broadcast:"+id+":1:SMS\",\"allowed\":false,\"reason\":\"NO_CONSENT\"}]";
  server.createContext("/api/v1/internal/notifications/broadcast-recipients",exchange->{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var stream=exchange.getResponseBody()){stream.write(bytes);}});
  assertThat(client.resolve(1,id,List.of("SMS"),List.of(1L))).hasSize(1).first().extracting(r->r.reason()).isEqualTo("NO_CONSENT");
 }
 @Test void onlyTwoPolicyRequestsCanOccupyNetworkCapacity() throws Exception {
  var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
  server.createContext("/api/v1/internal/notifications/broadcast-recipients",exchange->{entered.countDown();try { release.await();exchange.sendResponseHeaders(503,-1);exchange.close(); }catch(InterruptedException stop) { Thread.currentThread().interrupt(); }});
  var first=java.util.concurrent.CompletableFuture.runAsync(()->{try { client.resolve(1,UUID.randomUUID(),List.of("SMS"),List.of(1L)); }catch(Exception expected){}});
  var second=java.util.concurrent.CompletableFuture.runAsync(()->{try { client.resolve(1,UUID.randomUUID(),List.of("SMS"),List.of(1L)); }catch(Exception expected){}});
  try {
   assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->client.resolve(1,UUID.randomUUID(),List.of("SMS"),List.of(1L))).hasMessage("Recipient policy capacity unavailable");
  }finally { release.countDown();first.get(3,java.util.concurrent.TimeUnit.SECONDS);second.get(3,java.util.concurrent.TimeUnit.SECONDS); }
 }

}
