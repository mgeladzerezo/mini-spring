package io.minispring.core.type;

/**
 * Outcome of a generics-aware assignability check, ordered from worst to best.
 *
 * <p>The middle value exists because erasure leaves gaps: a bean declared as raw
 * {@code Repository} <em>might</em> be a {@code Repository<User>}. The container treats such a
 * bean as a fallback candidate, chosen only when no bean matches {@link #EXACT}ly.
 */
public enum Assignability {
    /** The types are provably incompatible. */
    NONE,
    /** Compatible by erasure, but some type argument of the candidate is unknown. */
    UNCHECKED,
    /** Compatible including every type argument. */
    EXACT;

    /** Combines two partial results: the weaker one decides. */
    public Assignability and(Assignability other) {
        return compareTo(other) <= 0 ? this : other;
    }
}
