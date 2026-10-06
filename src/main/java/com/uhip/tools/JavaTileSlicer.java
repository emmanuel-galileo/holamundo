package com.uhip.tools;

import java.io.*;
import java.nio.file.Path;

/** Compatible direct entry for the Java backend. Unified selection is in TileSlicer. */
public final class JavaTileSlicer {
    private JavaTileSlicer() {}
    public static void main(String[] args) throws IOException {
        if(args.length==0) runInteractive(new BufferedReader(new InputStreamReader(System.in)));
        else TileSlicer.run(args,SliceRequest.Engine.JAVA);
    }
    public static Path runInteractive(BufferedReader reader) throws IOException { return TileSlicer.runInteractive(reader,SliceRequest.Engine.JAVA); }
    public static Path deriveDefaultOutputDir(Path input) { return TileSlicer.deriveDefaultOutputDir(input); }
}
