package com.metrovoc.reprise.format;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Content-addressed server packs carried inside a recording. */
public final class EmbeddedPacks {
    public static final String URL_PREFIX = "reprise-pack:";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");

    static String path(String hash) throws IOException {
        if (!HASH.matcher(hash).matches()) throw new IOException("Invalid embedded resource pack hash");
        return "resourcepack/" + hash + ".zip";
    }

    static void copy(ZipFile source, ZipOutputStream out, String hash) throws IOException {
        String name = path(hash);
        out.putNextEntry(new ZipEntry(name));
        extract(source, hash, out);
    }

    static void extract(ZipFile source, String hash, OutputStream out) throws IOException {
        var entry = source.getEntry(path(hash));
        if (entry == null) throw new IOException("Missing embedded resource pack " + hash);
        try (var stream = new HashingInputStream(Hashing.sha1(), source.getInputStream(entry))) {
            stream.transferTo(out);
            if (!stream.hash().toString().equals(hash)) throw new IOException("Embedded resource pack hash mismatch");
        }
    }

    private EmbeddedPacks() {}
}
