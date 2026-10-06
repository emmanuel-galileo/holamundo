package com.uhip.imaging;

import com.uhip.imaging.codec.JpegEncoder;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** FIFO bounded in-flight tile buffers; worker errors always reach the coordinator. */
final class TileEncodingPool implements AutoCloseable {
    private final ExecutorService workers; private final ArrayDeque<Future<?>> pending=new ArrayDeque<>(); private final int capacity;
    TileEncodingPool(int count) { capacity=count*2; workers=Executors.newFixedThreadPool(count); }
    void submit(Path target,int w,int h,int[] pixels,int quality) throws IOException {
        if(pending.size()>=capacity) awaitFirst();
        pending.add(workers.submit(()-> { JpegEncoder.write(target,w,h,pixels,quality); return null; }));
    }
    void finish() throws IOException { while(!pending.isEmpty()) awaitFirst(); }
    private void awaitFirst() throws IOException {
        try { pending.removeFirst().get(); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Codificación cancelada"); }
        catch(ExecutionException ex) { Throwable cause=ex.getCause(); if(cause instanceof IOException io) throw io; throw new IOException("Falló codificador JPEG",cause); }
    }
    public void close() throws IOException {
        workers.shutdownNow(); boolean interrupted=Thread.interrupted();
        try { if(!workers.awaitTermination(30,TimeUnit.SECONDS)) throw new IOException("No finalizó el codificador JPEG"); }
        catch(InterruptedException ex) { interrupted=true; throw new InterruptedIOException("Cierre interrumpido"); }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
}
