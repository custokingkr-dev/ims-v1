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
public final class AsyncNotificationReportBody {
    private final Semaphore active=new Semaphore(2);
    private final long timeoutMillis,timeoutNanos;
    private final int maxBytes;
    private final String oversizedReason;
    public AsyncNotificationReportBody(Duration timeout) { this(timeout,8192,"Normalized report is too large"); }
    public AsyncNotificationReportBody(Duration timeout,int maxBytes,String oversizedReason) {
        if(timeout.isNegative() || timeout.isZero() || maxBytes<1 || maxBytes==Integer.MAX_VALUE || oversizedReason==null)
            throw new IllegalArgumentException("Invalid notification report reader bounds");
        timeoutMillis=timeout.toMillis();timeoutNanos=timeout.toNanos();this.maxBytes=maxBytes;this.oversizedReason=oversizedReason;
    }
    @FunctionalInterface public interface FailureWriter {
        void write(HttpServletResponse response,int status,String reason) throws IOException;
    }
    public void receive(HttpServletRequest request,HttpServletResponse response,Consumer<byte[]> action) throws IOException {
        receive(request,response,action,null);
    }
    public void receive(HttpServletRequest request,HttpServletResponse response,Consumer<byte[]> action,FailureWriter failures) throws IOException {
        if(request.getContentLengthLong()>maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,oversizedReason);
        if(!active.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report reader unavailable");
        final AsyncContext context;
        try { context=request.startAsync(request,response); }
        catch(RuntimeException unavailable) {active.release();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report reader unavailable");}
        final ServletInputStream input;
        try{input=request.getInputStream();}
        catch(IOException unavailable){active.release();completeSetup(context);throw unavailable;}
        catch(RuntimeException unavailable){active.release();completeSetup(context);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report reader unavailable");}
        var handler=new Handler(context,input,response,action,failures,System.nanoTime()+timeoutNanos);
        try{context.setTimeout(timeoutMillis);context.addListener(handler);input.setReadListener(handler);}
        catch(RuntimeException unavailable){handler.onError(unavailable);}
    }
    private static void completeSetup(AsyncContext context) {
        try{context.complete();}catch(RuntimeException completed){/* No reader or action was installed. */}
    }
    private final class Handler implements ReadListener,AsyncListener {
        private final AsyncContext context;
        private final ServletInputStream input;
        private final HttpServletResponse response;
        private final Consumer<byte[]> action;
        private final FailureWriter failures;
        private final long deadline;
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        private int phase; // 0 reading, 1 processing owned bytes, 2 complete; guarded by this.
        private boolean released;
        private boolean processing;
        Handler(AsyncContext context,ServletInputStream input,HttpServletResponse response,Consumer<byte[]> action,FailureWriter failures,long deadline) {
            this.context=context;this.input=input;this.response=response;this.action=action;this.failures=failures;this.deadline=deadline;
        }
        @Override public synchronized void onDataAvailable() throws IOException {
            byte[] part=new byte[512];
            while(phase==0) {
                if(System.nanoTime()>=deadline){finish(408);return;}
                if(!input.isReady() || input.isFinished())return;
                int count=input.read(part,0,Math.min(part.length,maxBytes+1-body.size()));
                if(count<0)return;
                if(count==0)return;
                body.write(part,0,count);
                if(body.size()>maxBytes){finish(413,oversizedReason);return;}
            }
        }
        @Override public void onAllDataRead() {
            final byte[] owned;
            synchronized(this) {
                if(phase!=0)return;
                if(System.nanoTime()>=deadline){finish(408);return;}
                // Stop the body timer. The action receives only owned bytes; SQL has its
                // own short transaction/statement deadlines and may be safely replayed.
                try{context.setTimeout(0);}
                catch(RuntimeException unavailable){finish(503);return;}
                owned=body.toByteArray();phase=1;processing=true;
            }
            int status=503;
            String reason="Notification report was not accepted";
            try {action.accept(owned);status=202;}
            catch(ResponseStatusException failure){status=failure.getStatusCode().value();reason=failure.getReason();}
            catch(RuntimeException unavailable){status=503;}
            finally {
                synchronized(this) {
                    try{if(phase==1)finish(status,reason);}
                    finally{processing=false;if(phase==2)release();}
                }
            }
        }
        private void finish(int status) {finish(status,"Notification report was not accepted");}
        private void finish(int status,String reason) {
            if(phase==2)return;
            phase=2;
            try {
                if(status!=202 && failures!=null) failures.write(response,status,reason);
                else {
                    response.setStatus(status);response.setContentType("application/json");
                    response.getWriter().write(status==202?"{\"accepted\":true}":"{\"error\":\"Notification report was not accepted\"}");
                }
            } catch(IOException disconnected) { /* No private report data is logged. */ }
            finally {
                try{context.complete();}
                finally{if(!processing)release();}
            }
        }
        private void release(){if(!released){released=true;active.release();}}
        @Override public synchronized void onError(Throwable failure){finish(503);}
        @Override public synchronized void onTimeout(AsyncEvent event){if(phase==0)finish(408);}
        @Override public synchronized void onError(AsyncEvent event){finish(503);}
        @Override public synchronized void onComplete(AsyncEvent event){phase=2;if(!processing)release();}
        @Override public void onStartAsync(AsyncEvent event){/* No dispatch/restart is used. */}
    }
}
