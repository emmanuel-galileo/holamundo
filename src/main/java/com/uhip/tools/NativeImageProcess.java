package com.uhip.tools;

import com.uhip.imaging.ImageLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Runs only the explicitly selected backend; interruption terminates its own child process. */
final class NativeImageProcess {
    private NativeImageProcess() {}
    static String capture(List<String> command) throws IOException {
        ImageLimits.checkpoint(); Path stdout=Files.createTempFile("uhip-vips-header-",".txt");
        try { return captureFile(command,stdout); } finally { Files.deleteIfExists(stdout); }
    }
    private static String captureFile(List<String> command,Path stdout) throws IOException {
        Process process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(stdout.toFile()).start();
        try {
            await(process); if(Files.size(stdout)>1024) throw new IOException("Respuesta de vipsheader excesiva");
            return Files.readString(stdout,StandardCharsets.UTF_8).trim();
        } finally { stopIfAlive(process); }
    }
    static void execute(List<String> command) throws IOException {
        ImageLimits.checkpoint(); Process process=new ProcessBuilder(command).inheritIO().start();
        try { await(process); } finally { stopIfAlive(process); }
    }
    private static void await(Process process) throws IOException {
        try { int code=process.waitFor(); if(code!=0) throw new IOException("Libvips finalizó con código "+code); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Procesamiento libvips cancelado"); }
    }
    private static void stopIfAlive(Process process) throws IOException {
        if(!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); boolean interrupted=Thread.interrupted();
        try { if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("No finalizó el proceso libvips"); }
        catch(InterruptedException ex) { interrupted=true; throw new InterruptedIOException("Cierre de libvips interrumpido"); }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
}
