package com.geek.association.source;

import java.util.List;
import java.util.Map;

public class EmptySource implements FileSource {

    @Override
    public List<String> files() {
        return List.of();
    }

    @Override
    public String read(String path) {
        return null;
    }

    @Override
    public String hash(String path) {
        return null;
    }

    @Override
    public boolean exists(String path) {
        return false;
    }

    @Override
    public String label() {
        return "empty";
    }
}
