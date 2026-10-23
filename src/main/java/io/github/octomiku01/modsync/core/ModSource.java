package io.github.octomiku01.modsync.core;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** A place mods are downloaded from */
public interface ModSource {
    /** The name entries use in their "source" field */
    String id();

    /** Where the entry's file can be downloaded, with the headers the request needs (like a token) */
    Download resolve(Manifest.Entry entry) throws IOException, InterruptedException;

    record Download(URI uri, Map<String, String> headers) {
    }
}
