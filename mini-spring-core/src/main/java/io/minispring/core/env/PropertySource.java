package io.minispring.core.env;

import java.util.Optional;

/** A named place to look property values up. The {@link Environment} asks its sources in order. */
public interface PropertySource {

    String name();

    Optional<String> get(String key);
}
