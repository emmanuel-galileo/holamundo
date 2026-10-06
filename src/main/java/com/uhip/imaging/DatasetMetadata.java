package com.uhip.imaging;

import java.io.IOException;
import java.nio.file.*;

/** Same four UHIP metadata fields for both engines; provenance stays outside the wire contract. */
public final class DatasetMetadata {
    private DatasetMetadata() {}
    public static void write(Path staging,ImageReaders.Info info,int zoom,PyramidJob.Options options,long tiles,String engine) throws IOException {
        String metadata="{\n  \"originalWidth\": "+info.width()+",\n  \"originalHeight\": "+info.height()+",\n  \"tileSize\": 256,\n  \"maxZoom\": "+zoom+"\n}\n";
        Files.writeString(staging.resolve("metadata.json"),metadata,StandardOpenOption.CREATE_NEW);
        String manifest="{\"engine\":\""+engine+"\",\"complete\":true,\"tileCount\":"+tiles+",\"jpegQuality\":"+options.quality()+",\"reducer\":\""+options.filter()+"\",\"sourceFormat\":\""+info.format()+"\",\"bufferBytes\":"+options.bufferBytes()+",\"workers\":"+options.workers()+"}\n";
        Files.writeString(staging.resolve("manifest.json"),manifest,StandardOpenOption.CREATE_NEW);
    }
}
