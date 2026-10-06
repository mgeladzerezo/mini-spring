package io.minispring.tx;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the manager needs to know about one transactional call: the attributes of
 * {@link Transactional} in a form that can also be built programmatically.
 *
 * @param name          shown in error messages and traces, usually {@code Class.method}
 * @param propagation   see {@link Propagation}
 * @param isolation     see {@link Isolation}
 * @param readOnly      see {@link Transactional#readOnly()}
 * @param rollbackFor   see {@link Transactional#rollbackFor()}
 * @param noRollbackFor see {@link Transactional#noRollbackFor()}
 */
public record TransactionDefinition(String name, Propagation propagation, Isolation isolation, boolean readOnly,
                                    List<Class<? extends Throwable>> rollbackFor,
                                    List<Class<? extends Throwable>> noRollbackFor) {

    public TransactionDefinition {
        rollbackFor = List.copyOf(rollbackFor);
        noRollbackFor = List.copyOf(noRollbackFor);
    }

    /** A definition with the given propagation and every other attribute at its default. */
    public static TransactionDefinition of(Propagation propagation) {
        return new TransactionDefinition("programmatic", propagation, Isolation.DEFAULT, false, List.of(), List.of());
    }

    public static TransactionDefinition from(Transactional annotation, String name) {
        return new TransactionDefinition(name, annotation.propagation(), annotation.isolation(), annotation.readOnly(),
                List.of(annotation.rollbackFor()), List.of(annotation.noRollbackFor()));
    }

    /**
     * Whether this exception rolls the transaction back.
     *
     * <p>Every rule matching the exception competes by how far its class is from the exception's
     * class in the inheritance chain (0 for the exact class); the closest rule wins, and on a tie
     * {@code rollbackFor} beats {@code noRollbackFor}. With no matching rule the default applies:
     * unchecked exceptions and errors roll back, checked exceptions do not. This makes
     * {@code @Transactional(rollbackFor = Exception.class, noRollbackFor = InsufficientFunds.class)}
     * do what it reads like, even though InsufficientFunds is also an Exception.
     */
    public boolean rollbackOn(Throwable failure) {
        int bestDepth = Integer.MAX_VALUE;
        boolean rollback = failure instanceof RuntimeException || failure instanceof Error;
        List<Rule> rules = new ArrayList<>();
        rollbackFor.forEach(type -> rules.add(new Rule(type, true)));
        noRollbackFor.forEach(type -> rules.add(new Rule(type, false)));
        for (Rule rule : rules) {
            int depth = depth(failure.getClass(), rule.type());
            if (depth >= 0 && depth < bestDepth) {
                bestDepth = depth;
                rollback = rule.rollback();
            }
        }
        return rollback;
    }

    private record Rule(Class<? extends Throwable> type, boolean rollback) {
    }

    /** Number of superclass steps from {@code from} up to {@code to}, or -1 if {@code to} is not an ancestor. */
    private static int depth(Class<?> from, Class<?> to) {
        int depth = 0;
        for (Class<?> current = from; current != null; current = current.getSuperclass(), depth++) {
            if (current == to) {
                return depth;
            }
        }
        return -1;
    }
}
