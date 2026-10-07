package com.custoking.ims.schoolcoreservice.outbox;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.TimeUnit;

@Component
public class PhotoCleanupWorker {
    private static final java.util.concurrent.ExecutorService OBJECT_CALLS = new java.util.concurrent.ThreadPoolExecutor(
            2,2,0,TimeUnit.SECONDS,new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                var thread=new Thread(runnable,"photo-cleanup-object");thread.setDaemon(true);return thread;
            },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final PhotoCleanupQueue queue;
    private final StudentPhotoStorage photos;
    private final TransactionTemplate transaction;
    public PhotoCleanupWorker(PhotoCleanupQueue queue, StudentPhotoStorage photos, PlatformTransactionManager manager) {
        this.queue=queue;this.photos=photos;this.transaction=new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(5);
    }
    public int drain() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Object cleanup cannot run inside a database transaction");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);int completed=0;
        for(int i=0;i<5 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();i++) {
            var optional=transaction.execute(status->queue.claim());if(optional==null||optional.isEmpty())break;
            var work=optional.get();
            try {
                boolean reused=Boolean.TRUE.equals(transaction.execute(status->queue.sourceIdentityReused(work)));
                if(reused){transaction.executeWithoutResult(status->queue.block(work));continue;}
                Long generation=work.generation();
                if(generation==null){
                    generation=objectCall(()->photos.photoCleanupGeneration(work.target()));
                    if(generation==null){if(Boolean.TRUE.equals(transaction.execute(status->queue.complete(work))))completed++;continue;}
                    long pinned=generation;
                    if(!Boolean.TRUE.equals(transaction.execute(status->queue.pin(work,pinned))))continue;
                }
                if(!Boolean.TRUE.equals(transaction.execute(status->queue.fenced(work))))continue;
                long pinned=generation;
                objectCall(()->{photos.deletePhotoGeneration(work.target(),pinned);return null;});
                if(Boolean.TRUE.equals(transaction.execute(status->queue.complete(work))))completed++;
            } catch(RuntimeException failure){
                // SDK failures can contain keys/credentials. Persist only this fixed retry reason.
                transaction.executeWithoutResult(status->queue.retry(work));
            }
        }
        return completed;
    }
    private static <T> T objectCall(java.util.concurrent.Callable<T> operation) {
        java.util.concurrent.Future<T> result=null;
        try {result=OBJECT_CALLS.submit(operation);return result.get(6,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Object cleanup interrupted");}
        catch(Exception failure){throw new IllegalStateException("Object cleanup attempt did not complete");}
        finally{if(result!=null && !result.isDone())result.cancel(true);}
    }
}
