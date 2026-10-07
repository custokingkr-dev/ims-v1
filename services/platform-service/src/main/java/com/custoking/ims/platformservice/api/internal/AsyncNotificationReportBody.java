package com.custoking.ims.platformservice.api.internal;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Container-owned nonblocking reads. No worker retains a recycled servlet stream. */
final class AsyncNotificationReportBody {
    private final Semaphore active=new Semaphore(2);
    private final long timeoutMillis,timeoutNanos;
    AsyncNotificationReportBody(Duration timeout) { timeoutMillis=timeout.toMillis();timeoutNanos=timeout.toNanos(); }
    void receive(HttpServletRequest request,HttpServletResponse response,Consumer<byte[]> action) throws IOException {
        if(request.getContentLengthLong()>8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Normalized report is too large");
        if(!active.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report reader unavailable");
        final AsyncContext context;
        try { context=request.startAsync(request,response); }
        catch(RuntimeException unavailable) {active.release();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report reader unavailable");}
        final ServletInputStream input;
        try{input=request.getInputStream();}
        catch(IOException unavailable){active.release();context.complete();throw unavailable;}
        var handler=new Handler(context,input,response,action,System.nanoTime()+timeoutNanos);
        try{context.setTimeout(timeoutMillis);context.addListener(handler);input.setReadListener(handler);}
        catch(RuntimeException unavailable){handler.onError(unavailable);}
    }
    private final class Handler implements ReadListener,AsyncListener {
        private final AsyncContext context;
        private final ServletInputStream input;
        private final HttpServletResponse response;
        private final Consumer<byte[]> action;
        private final long deadline;
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        private int phase; // 0 reading, 1 processing owned bytes, 2 complete; guarded by this.
        private boolean released;
        Handler(AsyncContext context,ServletInputStream input,HttpServletResponse response,Consumer<byte[]> action,long deadline) {
            this.context=context;this.input=input;this.response=response;this.action=action;this.deadline=deadline;
        }
        @Override public synchronized void onDataAvailable() throws IOException {
            byte[] part=new byte[512];
            while(phase==0) {
                if(System.nanoTime()>=deadline){finish(408);return;}
                if(!input.isReady() || input.isFinished())return;
                int count=input.read(part,0,Math.min(part.length,8193-body.size()));
                if(count<0)return;
                if(count==0)return;
                body.write(part,0,count);
                if(body.size()>8192){finish(413);return;}
            }
        }
        @Override public void onAllDataRead() {
            final byte[] owned;
            synchronized(this) {
                if(phase!=0)return;
                if(System.nanoTime()>=deadline){finish(408);return;}
                owned=body.toByteArray();phase=1;
                // Stop the body timer. The action receives only owned bytes; SQL has its
                // own short transaction/statement deadlines and may be safely replayed.
                context.setTimeout(0);
            }
            int status=202;
            try {action.accept(owned);}
            catch(ResponseStatusException failure){status=failure.getStatusCode().value();}
            catch(RuntimeException unavailable){status=503;}
            synchronized(this){if(phase==1)finish(status);}
        }
        private void finish(int status) {
            if(phase==2)return;
            phase=2;
            try {
                response.setStatus(status);response.setContentType("application/json");
                response.getWriter().write(status==202?"{\"accepted\":true}":"{\"error\":\"Notification report was not accepted\"}");
            } catch(IOException disconnected) { /* No private report data is logged. */ }
            finally {context.complete();}
        }
        @Override public synchronized void onError(Throwable failure){finish(503);}
        @Override public synchronized void onTimeout(AsyncEvent event){if(phase==0)finish(408);}
        @Override public synchronized void onError(AsyncEvent event){finish(503);}
        @Override public synchronized void onComplete(AsyncEvent event){phase=2;if(!released){released=true;active.release();}}
        @Override public void onStartAsync(AsyncEvent event){/* No dispatch/restart is used. */}
    }
}
