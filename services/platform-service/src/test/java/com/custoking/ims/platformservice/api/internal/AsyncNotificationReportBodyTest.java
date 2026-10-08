package com.custoking.ims.platformservice.api.internal;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncNotificationReportBodyTest {
    enum SetupFailure {START_ASYNC,GET_INPUT_IO,GET_INPUT_RUNTIME,SET_TIMEOUT,ADD_LISTENER,READ_LISTENER}
    private record Attempt(HttpServletRequest request,MockHttpServletResponse response,AsyncContext context,
                           AtomicReference<ReadListener> reader,AtomicReference<AsyncListener> completion){}
    private static Attempt attempt(SetupFailure failure) throws Exception {
        var request=mock(HttpServletRequest.class);var response=new MockHttpServletResponse();
        var context=mock(AsyncContext.class);var input=mock(ServletInputStream.class);
        var reader=new AtomicReference<ReadListener>();var completion=new AtomicReference<AsyncListener>();
        when(request.getContentLengthLong()).thenReturn(-1L);when(request.startAsync(request,response)).thenReturn(context);
        when(request.getInputStream()).thenReturn(input);when(input.isReady()).thenReturn(true);when(input.isFinished()).thenReturn(true);
        doAnswer(call->{completion.set(call.getArgument(0));return null;}).when(context).addListener(any(AsyncListener.class));
        doAnswer(call->{reader.set(call.getArgument(0));return null;}).when(input).setReadListener(any(ReadListener.class));
        doAnswer(call->{var listener=completion.get();if(listener!=null)listener.onComplete(new AsyncEvent(context));return null;}).when(context).complete();
        if(failure!=null)switch(failure) {
            case START_ASYNC -> when(request.startAsync(request,response)).thenThrow(new IllegalStateException("synthetic start failure"));
            case GET_INPUT_IO -> when(request.getInputStream()).thenThrow(new IOException("synthetic input failure"));
            case GET_INPUT_RUNTIME -> when(request.getInputStream()).thenThrow(new IllegalStateException("synthetic input state failure"));
            case SET_TIMEOUT -> doThrow(new IllegalStateException("synthetic timeout failure")).when(context).setTimeout(anyLong());
            case ADD_LISTENER -> doThrow(new IllegalStateException("synthetic listener failure")).when(context).addListener(any(AsyncListener.class));
            case READ_LISTENER -> doThrow(new IllegalStateException("synthetic read listener failure")).when(input).setReadListener(any(ReadListener.class));
        }
        return new Attempt(request,response,context,reader,completion);
    }
    private static void receive(AsyncNotificationReportBody body,Attempt attempt,java.util.function.Consumer<byte[]> action) throws IOException {
        body.receive(attempt.request,attempt.response,action);
    }
    private static void successfulThird(AsyncNotificationReportBody body) throws Exception {
        var healthy=attempt(null);var calls=new AtomicInteger();
        receive(body,healthy,bytes->calls.incrementAndGet());healthy.reader.get().onAllDataRead();
        assertThat(calls.get()).isEqualTo(1);assertThat(healthy.response.getStatus()).isEqualTo(202);
        assertThat(healthy.response.getContentAsString()).isEqualTo("{\"accepted\":true}");
    }
    @ParameterizedTest @EnumSource(SetupFailure.class)
    void twoSetupFailuresCannotLeakOrOverReleaseReaderCapacity(SetupFailure failure) throws Exception {
        var body=new AsyncNotificationReportBody(Duration.ofSeconds(5));var actions=new AtomicInteger();
        for(int i=0;i<2;i++) {
            var broken=attempt(failure);
            try{receive(body,broken,bytes->actions.incrementAndGet());}
            catch(IOException expected){}
            catch(ResponseStatusException expected){assertThat(expected.getStatusCode().value()).isEqualTo(503);}
        }
        assertThat(actions.get()).isZero();successfulThird(body);
        // Capacity remains exactly two even after setup failures and duplicate completion events.
        var first=attempt(null);var second=attempt(null);receive(body,first,bytes->{});receive(body,second,bytes->{});
        var denied=attempt(null);
        assertThatThrownBy(()->receive(body,denied,bytes->{})).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(503));
        first.reader.get().onError(new IOException("synthetic disconnect"));first.completion.get().onComplete(new AsyncEvent(first.context));
        second.reader.get().onError(new IOException("synthetic disconnect"));successfulThird(body);
    }
    @Test void failedBodyTimerResetNeverProcessesBytesOrLeaksCapacity() throws Exception {
        var body=new AsyncNotificationReportBody(Duration.ofSeconds(5));var actions=new AtomicInteger();
        for(int i=0;i<2;i++) {
            var broken=attempt(null);doThrow(new IllegalStateException("synthetic reset failure")).when(broken.context).setTimeout(0);
            receive(body,broken,bytes->actions.incrementAndGet());broken.reader.get().onAllDataRead();
            assertThat(broken.response.getStatus()).isEqualTo(503);
        }
        assertThat(actions.get()).isZero();successfulThird(body);
    }
    @Test void disconnectedResponsesKeepTheirSlotsUntilOwnedActionsActuallyExit() throws Exception {
        var body=new AsyncNotificationReportBody(Duration.ofSeconds(5));var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var first=attempt(null);var second=attempt(null);
        java.util.function.Consumer<byte[]> blocked=bytes->{entered.countDown();try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException failure){throw new IllegalStateException(failure);}};
        receive(body,first,blocked);receive(body,second,blocked);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var one=workers.submit(()->{first.reader.get().onAllDataRead();return null;});
            var two=workers.submit(()->{second.reader.get().onAllDataRead();return null;});
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                first.reader.get().onError(new IOException("synthetic disconnect"));second.reader.get().onError(new IOException("synthetic disconnect"));
                var denied=attempt(null);
                assertThatThrownBy(()->receive(body,denied,bytes->{})).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(503));
            } finally {release.countDown();one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);}
        }
        successfulThird(body);
    }
}
