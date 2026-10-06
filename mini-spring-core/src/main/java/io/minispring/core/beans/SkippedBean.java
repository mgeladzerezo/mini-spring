package io.minispring.core.beans;

import io.minispring.core.type.ResolvedType;

/**
 * A component that was found but not registered because a condition did not match. Kept so a
 * later "no such bean" error can say why the obvious candidate is missing.
 */
public record SkippedBean(String name, ResolvedType type, String reason) {
}
