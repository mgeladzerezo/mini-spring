package io.minispring.core.fixtures.scan;

import io.minispring.core.annotation.Service;

@Service
public class ScannedService {

    public String origin() {
        return "scanned";
    }
}
