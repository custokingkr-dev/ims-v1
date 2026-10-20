package com.custoking.ims.schoolcoreservice.photoimport;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GoogleDriveBoundedHttpTest {
    private GoogleDrivePhotoImportClient client(HttpServer server) {
        var client = new GoogleDrivePhotoImportClient(new ObjectMapper(), true, "root", "client", "secret", "refresh", "user");
        ReflectionTestUtils.setField(client, "api", "http://127.0.0.1:" + server.getAddress().getPort() + "/files");
        var credentials=org.mockito.Mockito.mock(GoogleCredentials.class);
        org.mockito.Mockito.when(credentials.getAccessToken()).thenReturn(new AccessToken("controlled-offline-token", new Date(System.currentTimeMillis()+3_600_000)));
        ReflectionTestUtils.setField(client, "credentials", credentials);
        return client;
    }
    private HttpServer server() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }));
        return server;
    }
    private static Map<String,Object> file(int i) { return Map.of("id", "file"+i,"name","DSC"+i+".png","mimeType","image/png","size","100"); }
    @Test void legitimateThreeHundredFilesRemainSupportedAndPageFileTokenCapsStopActualRequests() throws Exception {
        var server=server(); var calls=new AtomicInteger();
        var files=new ArrayList<Map<String,Object>>(); for(int i=0;i<300;i++) files.add(file(i));
        server.createContext("/files", x -> {
            calls.incrementAndGet(); boolean second=x.getRequestURI().getRawQuery().contains("pageToken=");
            byte[] bytes=new ObjectMapper().writeValueAsBytes(second ? Map.of("files",List.of(file(300))) : Map.of("files",files,"nextPageToken","page2"));
            x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
        });server.start();
        try {
            var client=client(server);assertThat(client.listFiles("folder")).hasSize(301);assertThat(calls.get()).isEqualTo(2);
            ReflectionTestUtils.setField(client,"maxListedFiles",300);
            assertThatThrownBy(() -> client.listFiles("folder")).isInstanceOf(DrivePhotoImportException.class).extracting(e -> ((DrivePhotoImportException)e).code()).isEqualTo("folder_too_large");
            ReflectionTestUtils.setField(client,"maxListedFiles",5000);ReflectionTestUtils.setField(client,"maxListPages",1);int before=calls.get();
            assertThatThrownBy(() -> client.listFiles("folder")).isInstanceOf(DrivePhotoImportException.class);
            assertThat(calls.get()-before).isEqualTo(1);
        }finally{server.stop(0);}
    }
    @Test void getAndPostBodiesAreBoundedThroughTheActualJdkHttpClient() throws Exception {
        var server=server();server.createContext("/files",x->{byte[] bytes=("{\"x\":\""+"a".repeat(500)+"\"}").getBytes();x.sendResponseHeaders(200,0);x.getResponseBody().write(bytes);x.close();});server.start();
        try {
            var client=client(server);ReflectionTestUtils.setField(client,"jsonMaxBytes",128);String uri="http://127.0.0.1:"+server.getAddress().getPort()+"/files";
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client,"jsonGet",uri)).isInstanceOf(DrivePhotoImportException.class).hasMessageNotContaining("controlled-offline-token");
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client,"jsonPost",uri,Map.of("name","synthetic"))).isInstanceOf(DrivePhotoImportException.class);
        }finally{server.stop(0);}
    }
    @Test void scanDeadlineCancelsTrickledBodyAndReleasesAdmission() throws Exception {
        var server=server();var slow=new java.util.concurrent.atomic.AtomicBoolean(true);
        server.createContext("/files",x->{
            x.sendResponseHeaders(200,0);
            try {if(slow.get()){x.getResponseBody().write('{');for(int i=0;i<50;i++){x.getResponseBody().write(' ');x.getResponseBody().flush();Thread.sleep(100);}}else{x.getResponseBody().write("{\"files\":[]}".getBytes());}}
            catch(Exception ignored){}finally{x.close();}
        });server.start();
        try {
            var client=client(server);ReflectionTestUtils.setField(client,"listTimeoutSeconds",1);long started=System.nanoTime();
            assertThatThrownBy(() -> client.listFiles("folder")).isInstanceOf(DrivePhotoImportException.class);
            assertThat((System.nanoTime()-started)/1_000_000).isBetween(850L,2500L);
            slow.set(false);assertThat(client.listFiles("folder")).isEmpty();
        }finally{server.stop(0);}
    }
    @Test void repeatedPageTokenCannotLoopThroughProviderPages() throws Exception {
        var server=server();var calls=new AtomicInteger();server.createContext("/files",x->{calls.incrementAndGet();byte[] bytes="{\"files\":[],\"nextPageToken\":\"repeat\"}".getBytes();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
        try {assertThatThrownBy(()->client(server).listFiles("folder")).isInstanceOf(DrivePhotoImportException.class);assertThat(calls.get()).isEqualTo(2);}finally{server.stop(0);}
    }
}
