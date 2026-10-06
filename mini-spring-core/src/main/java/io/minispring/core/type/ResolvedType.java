package io.minispring.core.type;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link java.lang.reflect.Type} with its type variables substituted, plus the subtyping
 * rules needed to decide whether one generic type can be injected where another is expected.
 *
 * <p>Erasure removes type arguments from <em>objects</em>, but not from <em>declarations</em>:
 * class files still record that {@code UserRepository extends JdbcRepository<User>}, that
 * {@code JdbcRepository<T> implements Repository<T>} and that a field is a
 * {@code Repository<User>}. This class reads those declarations and connects them:
 *
 * <ul>
 *   <li>{@link #as(Class)} walks up a class hierarchy carrying bindings along, so
 *       {@code forClass(UserRepository.class).as(Repository.class)} is {@code Repository<User>};</li>
 *   <li>the {@code for...} factories resolve a member's type <em>in the context of</em> the
 *       concrete class it is used from, so a field {@code Repository<T>} declared in
 *       {@code BaseService<T>} is {@code Repository<User>} when seen from
 *       {@code UserService extends BaseService<User>};</li>
 *   <li>{@link #assignabilityFrom(ResolvedType)} applies Java's containment rules: type
 *       arguments are invariant unless the target uses a wildcard.</li>
 * </ul>
 *
 * <p>A type argument nobody bound (a raw type, or a variable of a generic bean class) is kept as
 * an <em>unresolved variable</em> remembering its upper bound. Matching against it yields
 * {@link Assignability#UNCHECKED} rather than a hard yes or no, which lets the container prefer
 * a precisely typed bean and still fall back to a raw one, mirroring the compiler's unchecked
 * conversion.
 *
 * <p>Instances are immutable. Limits: only the first bound of {@code T extends A & B} is
 * considered, and variables declared by an enclosing class of an inner class are not bound.
 */
public final class ResolvedType {

    /** How a type argument may vary: exactly this type, any subtype, or any supertype. */
    public enum Variance {
        INVARIANT, EXTENDS, SUPER
    }

    private static final ResolvedType OBJECT = new ResolvedType(Object.class, List.of(), null, Variance.INVARIANT, null);
    private static final ResolvedType UNKNOWN = new ResolvedType(Object.class, List.of(), null, Variance.EXTENDS, "?");

    private final Class<?> raw;
    private final List<ResolvedType> arguments;
    private final ResolvedType component;
    private final Variance variance;
    /** Name of the type variable this stands in for when nothing bound it; {@code null} otherwise. */
    private final String variable;

    private ResolvedType(Class<?> raw, List<ResolvedType> arguments, ResolvedType component, Variance variance,
                         String variable) {
        this.raw = raw;
        this.arguments = List.copyOf(arguments);
        this.component = component;
        this.variance = variance;
        this.variable = variable;
    }

    // ---------------------------------------------------------------- factories

    /** Resolves a class on its own; a generic class gets unresolved variables as arguments. */
    public static ResolvedType forClass(Class<?> type) {
        return resolve(Objects.requireNonNull(type, "type"), null, new HashSet<>());
    }

    /** Resolves a reflective type with no surrounding context. */
    public static ResolvedType forType(Type type) {
        return resolve(Objects.requireNonNull(type, "type"), null, new HashSet<>());
    }

    /**
     * Resolves a reflective type as seen from {@code context}: type variables declared by
     * {@code context}'s class or any of its supertypes are replaced by what {@code context} binds.
     */
    public static ResolvedType forType(Type type, ResolvedType context) {
        return resolve(Objects.requireNonNull(type, "type"), context, new HashSet<>());
    }

    /** The field's type as seen from {@code implementationClass}, a subclass of the declaring class. */
    public static ResolvedType forField(Field field, Class<?> implementationClass) {
        return forType(field.getGenericType(), forClass(implementationClass));
    }

    /** The parameter's type as seen from {@code implementationClass}. */
    public static ResolvedType forParameter(Parameter parameter, Class<?> implementationClass) {
        return forType(parameter.getParameterizedType(), forClass(implementationClass));
    }

    /** The method's return type as seen from {@code implementationClass}. */
    public static ResolvedType forReturnType(Method method, Class<?> implementationClass) {
        return forType(method.getGenericReturnType(), forClass(implementationClass));
    }

    /** Builds {@code raw<arguments...>} programmatically. */
    public static ResolvedType parameterized(Class<?> raw, ResolvedType... arguments) {
        if (raw.getTypeParameters().length != arguments.length) {
            throw new IllegalArgumentException(raw.getName() + " declares " + raw.getTypeParameters().length
                    + " type parameter(s) but " + arguments.length + " argument(s) were given");
        }
        return new ResolvedType(raw, List.of(arguments), null, Variance.INVARIANT, null);
    }

    /** Builds {@code raw<arguments...>} from plain classes. */
    public static ResolvedType parameterized(Class<?> raw, Class<?> firstArgument, Class<?>... moreArguments) {
        ResolvedType[] resolved = new ResolvedType[moreArguments.length + 1];
        resolved[0] = forClass(firstArgument);
        for (int i = 0; i < moreArguments.length; i++) {
            resolved[i + 1] = forClass(moreArguments[i]);
        }
        return parameterized(raw, resolved);
    }

    /** The most precise type known for a live object, honouring {@link ResolvableTypeProvider}. */
    public static ResolvedType forInstance(Object instance) {
        return instance instanceof ResolvableTypeProvider provider
                ? provider.getResolvedType()
                : forClass(instance.getClass());
    }

    // ---------------------------------------------------------------- resolution

    private static ResolvedType resolve(Type type, ResolvedType context, Set<TypeVariable<?>> inProgress) {
        return switch (type) {
            case Class<?> c when c.isArray() -> arrayOf(resolve(c.getComponentType(), context, inProgress));
            case Class<?> c -> rawClass(c, inProgress);
            case ParameterizedType p -> {
                List<ResolvedType> resolvedArguments = new ArrayList<>();
                for (Type argument : p.getActualTypeArguments()) {
                    resolvedArguments.add(resolve(argument, context, inProgress));
                }
                yield new ResolvedType((Class<?>) p.getRawType(), resolvedArguments, null, Variance.INVARIANT, null);
            }
            case GenericArrayType g -> arrayOf(resolve(g.getGenericComponentType(), context, inProgress));
            case WildcardType w -> w.getLowerBounds().length > 0
                    ? resolve(w.getLowerBounds()[0], context, inProgress).withVariance(Variance.SUPER)
                    : resolve(w.getUpperBounds()[0], context, inProgress).withVariance(Variance.EXTENDS);
            case TypeVariable<?> v -> resolveVariable(v, context, inProgress);
            default -> throw new IllegalArgumentException("Unsupported Type implementation: " + type.getClass());
        };
    }

    private static ResolvedType rawClass(Class<?> type, Set<TypeVariable<?>> inProgress) {
        TypeVariable<?>[] parameters = type.getTypeParameters();
        if (parameters.length == 0) {
            return new ResolvedType(type, List.of(), null, Variance.INVARIANT, null);
        }
        List<ResolvedType> unresolved = new ArrayList<>(parameters.length);
        for (TypeVariable<?> parameter : parameters) {
            unresolved.add(unresolved(parameter, null, inProgress));
        }
        return new ResolvedType(type, unresolved, null, Variance.INVARIANT, null);
    }

    /**
     * Looks a variable up in the context. The variable belongs to some class {@code D}; viewing
     * the context as {@code D} (which itself resolves supertypes recursively) yields D's
     * arguments in declaration order, and the variable's index selects the right one.
     */
    private static ResolvedType resolveVariable(TypeVariable<?> variable, ResolvedType context,
                                                Set<TypeVariable<?>> inProgress) {
        if (context != null && variable.getGenericDeclaration() instanceof Class<?> declaring) {
            ResolvedType view = context.as(declaring);
            if (view != null && !view.arguments.isEmpty()) {
                TypeVariable<?>[] parameters = declaring.getTypeParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (parameters[i].equals(variable)) {
                        return view.arguments.get(i);
                    }
                }
            }
        }
        return unresolved(variable, context, inProgress);
    }

    /**
     * Represents a variable nobody bound by its first upper bound. The {@code inProgress} set
     * stops self-referential bounds such as {@code T extends Comparable<T>} from recursing forever.
     */
    private static ResolvedType unresolved(TypeVariable<?> variable, ResolvedType context,
                                           Set<TypeVariable<?>> inProgress) {
        if (!inProgress.add(variable)) {
            return new ResolvedType(Object.class, List.of(), null, Variance.EXTENDS, variable.getName());
        }
        try {
            ResolvedType bound = resolve(variable.getBounds()[0], context, inProgress);
            return new ResolvedType(bound.raw, bound.arguments, bound.component, Variance.EXTENDS, variable.getName());
        } finally {
            inProgress.remove(variable);
        }
    }

    private static ResolvedType arrayOf(ResolvedType component) {
        return new ResolvedType(component.raw.arrayType(), List.of(), component, Variance.INVARIANT, null);
    }

    private ResolvedType withVariance(Variance newVariance) {
        // "? super T" or "? extends T" with T unbound tells us nothing more than T itself.
        return variable != null ? this : new ResolvedType(raw, arguments, component, newVariance, null);
    }

    /**
     * Views this type as one of its supertypes, carrying type arguments along.
     *
     * @return the parameterisation of {@code target} that this type implements, or {@code null}
     *         if this type is not a subtype of {@code target}
     */
    public ResolvedType as(Class<?> target) {
        if (raw == target) {
            return this;
        }
        if (!target.isAssignableFrom(raw)) {
            return null;
        }
        if (target == Object.class) {
            return OBJECT;
        }
        if (component != null) {
            return forClass(target); // arrays only implement Cloneable and Serializable
        }
        Type superclass = raw.getGenericSuperclass();
        if (superclass != null) {
            ResolvedType found = resolve(superclass, this, new HashSet<>()).as(target);
            if (found != null) {
                return found;
            }
        }
        for (Type implemented : raw.getGenericInterfaces()) {
            ResolvedType found = resolve(implemented, this, new HashSet<>()).as(target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- assignability

    /** Whether a value of type {@code candidate} may be used where this type is expected. */
    public boolean isAssignableFrom(ResolvedType candidate) {
        return assignabilityFrom(candidate) != Assignability.NONE;
    }

    /** Shorthand for {@code isAssignableFrom(ResolvedType.forClass(candidate))}. */
    public boolean isAssignableFrom(Class<?> candidate) {
        return isAssignableFrom(forClass(candidate));
    }

    /**
     * Decides whether {@code candidate} fits this type and how certain that is.
     *
     * @see Assignability
     */
    public Assignability assignabilityFrom(ResolvedType candidate) {
        return assignable(this, candidate);
    }

    private static Assignability assignable(ResolvedType target, ResolvedType candidate) {
        Class<?> targetRaw = box(target.raw);
        Class<?> candidateRaw = box(candidate.raw);
        if (!targetRaw.isAssignableFrom(candidateRaw)) {
            return Assignability.NONE;
        }
        if (target.component != null) {
            if (candidate.component == null) {
                return Assignability.NONE;
            }
            if (target.component.raw.isPrimitive() || candidate.component.raw.isPrimitive()) {
                return Assignability.EXACT; // int[] is only assignable to int[]; the raw check covered it
            }
            return assignable(target.component, candidate.component); // arrays are covariant
        }
        if (target.arguments.isEmpty()) {
            return Assignability.EXACT;
        }
        ResolvedType view = (candidate.raw.isPrimitive() ? forClass(candidateRaw) : candidate).as(targetRaw);
        if (view == null || view.arguments.size() != target.arguments.size()) {
            return Assignability.UNCHECKED;
        }
        Assignability result = Assignability.EXACT;
        for (int i = 0; i < target.arguments.size(); i++) {
            result = result.and(contains(target.arguments.get(i), view.arguments.get(i)));
            if (result == Assignability.NONE) {
                break;
            }
        }
        return result;
    }

    /**
     * Type-argument containment (JLS 4.5.1): does the target argument {@code expected} admit the
     * candidate's argument {@code actual}? This is where {@code List<Integer>} is rejected for
     * {@code List<Number>} but accepted for {@code List<? extends Number>}.
     */
    private static Assignability contains(ResolvedType expected, ResolvedType actual) {
        if (actual.variable != null) {
            // The candidate cannot say what this argument is. It may still be ruled out by its bound:
            // a raw Box<T extends Number> can never be a Box<String>.
            if (expected.variance == Variance.SUPER) {
                return Assignability.UNCHECKED;
            }
            Class<?> bound = box(actual.raw);
            Class<?> wanted = box(expected.raw);
            boolean overlap = bound.isAssignableFrom(wanted)
                    || (expected.variance == Variance.EXTENDS && wanted.isAssignableFrom(bound));
            return overlap ? Assignability.UNCHECKED : Assignability.NONE;
        }
        return switch (expected.variance) {
            case EXTENDS -> actual.variance == Variance.SUPER
                    ? (expected.raw == Object.class ? Assignability.EXACT : Assignability.NONE)
                    : assignable(expected, actual);
            case SUPER -> actual.variance == Variance.EXTENDS ? Assignability.NONE : assignable(actual, expected);
            case INVARIANT -> {
                if (actual.variance != Variance.INVARIANT || expected.raw != actual.raw) {
                    yield Assignability.NONE;
                }
                if (expected.component != null) {
                    yield expected.component.raw.isPrimitive()
                            ? Assignability.EXACT
                            : contains(expected.component, actual.component);
                }
                Assignability result = Assignability.EXACT;
                for (int i = 0; i < expected.arguments.size() && result != Assignability.NONE; i++) {
                    result = result.and(contains(expected.arguments.get(i), actual.arguments.get(i)));
                }
                yield result;
            }
        };
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        } else if (type == long.class) {
            return Long.class;
        } else if (type == boolean.class) {
            return Boolean.class;
        } else if (type == double.class) {
            return Double.class;
        } else if (type == float.class) {
            return Float.class;
        } else if (type == char.class) {
            return Character.class;
        } else if (type == byte.class) {
            return Byte.class;
        } else if (type == short.class) {
            return Short.class;
        }
        return Void.class;
    }

    // ---------------------------------------------------------------- accessors

    /** The erasure: the class of an object of this type (the upper bound for wildcards and variables). */
    public Class<?> rawClass() {
        return raw;
    }

    /** The erasure with primitives replaced by their wrapper classes. */
    public Class<?> boxedRawClass() {
        return box(raw);
    }

    /** Type arguments in declaration order; empty for non-generic types and arrays. */
    public List<ResolvedType> typeArguments() {
        return arguments;
    }

    /** The {@code index}-th type argument, or an unknown ({@code ?}) if there is none. */
    public ResolvedType typeArgument(int index) {
        return index < arguments.size() ? arguments.get(index) : UNKNOWN;
    }

    public boolean hasTypeArguments() {
        return !arguments.isEmpty();
    }

    public boolean isArray() {
        return component != null;
    }

    /** The element type of an array type, or {@code null}. */
    public ResolvedType componentType() {
        return component;
    }

    public Variance variance() {
        return variance;
    }

    /** Whether this stands in for a type variable that nothing bound. */
    public boolean isUnresolvedVariable() {
        return variable != null;
    }

    /** Whether neither this type nor any nested argument is an unresolved variable. */
    public boolean isFullyResolved() {
        if (variable != null) {
            return false;
        }
        if (component != null) {
            return component.isFullyResolved();
        }
        return arguments.stream().allMatch(ResolvedType::isFullyResolved);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResolvedType that
                && raw == that.raw
                && variance == that.variance
                && Objects.equals(variable, that.variable)
                && arguments.equals(that.arguments)
                && Objects.equals(component, that.component);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, variance, variable, arguments, component);
    }

    /** Source-like rendering with simple names, e.g. {@code Map<String, List<? extends Number>>}. */
    @Override
    public String toString() {
        if (variable != null) {
            return variable;
        }
        String body;
        if (component != null) {
            body = component + "[]";
        } else {
            body = simpleName(raw);
            if (!arguments.isEmpty()) {
                body += arguments.stream().map(ResolvedType::toString).collect(Collectors.joining(", ", "<", ">"));
            }
        }
        return switch (variance) {
            case INVARIANT -> body;
            case EXTENDS -> raw == Object.class && component == null ? "?" : "? extends " + body;
            case SUPER -> "? super " + body;
        };
    }

    private static String simpleName(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null ? type.getSimpleName() : simpleName(enclosing) + "." + type.getSimpleName();
    }
}
