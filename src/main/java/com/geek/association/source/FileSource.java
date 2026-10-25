package com.geek.association.source;

import java.util.List;

/** An immutable, read-only view of a source tree at one revision. */
public interface FileSource {

    List<String> files();

    String read(String path);

    String hash(String path);

    boolean exists(String path);

    String label();
}
