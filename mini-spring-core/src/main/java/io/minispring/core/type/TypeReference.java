package io.minispring.core.type;

/**
 * A super type token: captures a generic type that a {@code Class} literal cannot express.
 *
 * <pre>{@code
 * Repository<User> users = context.getBean(new TypeReference<Repository<User>>() {});
 * }</pre>
 *
 * The anonymous subclass records {@code TypeReference<Repository<User>>} as its generic
 * superclass, and that declaration survives erasure. The lookup is done with
 * {@link ResolvedType#as(Class)}, so it also works through intermediate subclasses.
 *
 * @param <T> the captured type
 */
public abstract class TypeReference<T> {

    private final ResolvedType type;

    protected TypeReference() {
        ResolvedType captured = ResolvedType.forClass(getClass()).as(TypeReference.class).typeArgument(0);
        if (!captured.isFullyResolved()) {
            throw new IllegalStateException("TypeReference needs a concrete type argument, "
                    + "e.g. new TypeReference<List<String>>() {}; got " + captured);
        }
        this.type = captured;
    }

    /** The captured type with all variables resolved. */
    public final ResolvedType resolved() {
        return type;
    }

    @Override
    public String toString() {
        return "TypeReference<" + type + ">";
    }
}
