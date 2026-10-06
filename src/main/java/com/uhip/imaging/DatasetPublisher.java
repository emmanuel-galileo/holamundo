package com.uhip.imaging;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** Shared transactional publication for either image engine. */
public final class DatasetPublisher {
    @FunctionalInterface public interface Writer { void write(Path staging) throws IOException; }
    private DatasetPublisher() {}
    public static Path publish(Path destination,Writer writer) throws IOException {
        Path output=destination.toAbsolutePath().normalize();
        if(Files.exists(output)) throw new IOException("El destino ya existe; use una carpeta nueva: "+output);
        Files.createDirectories(output.getParent()); Path lock=output.resolveSibling("."+output.getFileName()+".uhip.lock"); Files.createFile(lock);
        try { return publishLocked(output,writer); } finally { Files.deleteIfExists(lock); }
    }
    private static Path publishLocked(Path output,Writer writer) throws IOException {
        Path staging=output.resolveSibling("."+output.getFileName()+".uhip-"+UUID.randomUUID()); Files.createDirectory(staging);
        try {
            writer.write(staging); ImageLimits.checkpoint();
            if(Files.exists(output)) throw new IOException("El destino apareció durante la generación");
            Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE); System.out.println("[UHIP] Dataset listo: "+output); return output;
        } finally { removeOwnedTree(staging); }
    }
    public static void removeOwnedTree(Path path) throws IOException {
        if(!Files.exists(path)) return;
        Files.walkFileTree(path,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException { if(failure!=null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
