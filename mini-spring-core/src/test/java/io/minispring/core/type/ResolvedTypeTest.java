package io.minispring.core.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the hand-written generic type resolution: binding type variables through class
 * hierarchies, resolving members from the point of view of a subclass, and the containment
 * rules that make {@code Repository<User>} different from {@code Repository<Order>}.
 */
class ResolvedTypeTest {

    // ---- fixture hierarchy -------------------------------------------------------------

    interface Entity {
    }

    static class User implements Entity {
    }

    static class Order implements Entity {
    }

    interface Repository<T> {
    }

    interface CrudRepository<T, ID> extends Repository<T> {
    }

    abstract static class JdbcRepository<E extends Entity> implements CrudRepository<E, Long> {
        List<E> cache;

        E[] buffer;

        Map<String, ? extends Collection<E>> index;

        abstract E findById(Long id);

        abstract void saveAll(List<? super E> sink, Function<E, String> namer);
    }

    static class UserRepository extends JdbcRepository<User> {
        @Override
        User findById(Long id) {
            return null;
        }

        @Override
        void saveAll(List<? super User> sink, Function<User, String> namer) {
        }
    }

    interface Pair<A, B> {
    }

    abstract static class Swapped<X, Y> implements Pair<Y, X> {
    }

    abstract static class Middle<Z> extends Swapped<Z, Integer> {
    }

    static class Leaf extends Middle<String> {
    }

    interface Handler<T> {
    }

    static class DeepHandler implements Handler<List<Map<String, Integer[]>>> {
    }

    static class Box<T extends Number> implements Handler<T> {
    }

    static class Node<T extends Comparable<T>> {
        T value;
    }

    static class Outer<T> {
        class Inner {
            T value;
        }
    }

    @SuppressWarnings("rawtypes")
    static class RawList extends ArrayList {
    }

    static class Fields {
        List<? extends Number> numbers;
        List<? super Integer> integerSinks;
        List<Number> exactNumbers;
        List<?> anything;
        List<String>[] arrayOfLists;
        Map<String, List<Handler<User>>> nested;
        int primitive;
    }

    private static ResolvedType field(Class<?> owner, String name) throws NoSuchFieldException {
        return ResolvedType.forField(owner.getDeclaredField(name), owner);
    }

    private static ResolvedType listOf(Class<?> element) {
        return ResolvedType.parameterized(List.class, element);
    }

    // ---- hierarchy walking ---------------------------------------------------------------

    @Nested
    class HierarchyBinding {

        @Test
        void bindsVariableThroughSuperclassAndTwoInterfaces() {
            ResolvedType repository = ResolvedType.forClass(UserRepository.class).as(Repository.class);

            assertEquals("ResolvedTypeTest.Repository<ResolvedTypeTest.User>", repository.toString());
            assertEquals(User.class, repository.typeArgument(0).rawClass());
            assertTrue(repository.isFullyResolved());
        }

        @Test
        void bindsBothArgumentsOfAnIntermediateInterface() {
            ResolvedType crud = ResolvedType.forClass(UserRepository.class).as(CrudRepository.class);

            assertEquals(List.of(ResolvedType.forClass(User.class), ResolvedType.forClass(Long.class)),
                    crud.typeArguments());
        }

        @Test
        void followsVariablesThatAreReorderedAndPartiallyBoundOnTheWayUp() {
            // Leaf -> Middle<String> -> Swapped<String, Integer> -> Pair<Integer, String>
            ResolvedType pair = ResolvedType.forClass(Leaf.class).as(Pair.class);

            assertEquals(Integer.class, pair.typeArgument(0).rawClass());
            assertEquals(String.class, pair.typeArgument(1).rawClass());
        }

        @Test
        void keepsNestedParameterizedArgumentsIntact() {
            ResolvedType handler = ResolvedType.forClass(DeepHandler.class).as(Handler.class);

            assertEquals("ResolvedTypeTest.Handler<List<Map<String, Integer[]>>>", handler.toString());
            ResolvedType array = handler.typeArgument(0).typeArgument(0).typeArgument(1);
            assertTrue(array.isArray());
            assertEquals(Integer.class, array.componentType().rawClass());
        }

        @Test
        void returnsNullForUnrelatedTypesAndObjectForAnything() {
            assertNull(ResolvedType.forClass(UserRepository.class).as(Handler.class));
            assertEquals(Object.class, ResolvedType.forClass(Repository.class).as(Object.class).rawClass());
        }

        @Test
        void viewsAnArrayOnlyAsTheInterfacesArraysImplement() {
            ResolvedType array = ResolvedType.forClass(String[].class);

            assertEquals(Serializable.class, array.as(Serializable.class).rawClass());
            assertNull(array.as(List.class));
        }

        @Test
        void leavesVariablesOfAGenericClassUnresolvedButRemembersTheirBound() {
            ResolvedType repository = ResolvedType.forClass(JdbcRepository.class).as(Repository.class);
            ResolvedType argument = repository.typeArgument(0);

            assertTrue(argument.isUnresolvedVariable());
            assertEquals(Entity.class, argument.rawClass(), "erasure of E extends Entity");
            assertEquals("E", argument.toString());
            assertFalse(repository.isFullyResolved());
        }

        @Test
        void treatsARawSupertypeAsHavingUnknownArguments() {
            ResolvedType list = ResolvedType.forClass(RawList.class).as(List.class);

            assertTrue(list.typeArgument(0).isUnresolvedVariable());
        }

        @Test
        void survivesSelfReferentialBounds() {
            ResolvedType node = ResolvedType.forClass(Node.class);
            assertEquals(Comparable.class, node.typeArgument(0).rawClass());

            ResolvedType enumType = ResolvedType.forClass(Enum.class); // Enum<E extends Enum<E>>
            assertEquals(Enum.class, enumType.typeArgument(0).rawClass());
        }
    }

    // ---- members seen from a subclass ----------------------------------------------------

    @Nested
    class MembersInContext {

        @Test
        void resolvesAFieldDeclaredInAGenericSuperclass() throws Exception {
            ResolvedType cache = ResolvedType.forField(JdbcRepository.class.getDeclaredField("cache"),
                    UserRepository.class);

            assertEquals(listOf(User.class), cache);
        }

        @Test
        void resolvesAGenericArrayField() throws Exception {
            ResolvedType buffer = ResolvedType.forField(JdbcRepository.class.getDeclaredField("buffer"),
                    UserRepository.class);

            assertEquals(User[].class, buffer.rawClass());
            assertEquals(User.class, buffer.componentType().rawClass());
        }

        @Test
        void resolvesVariablesInsideWildcardBounds() throws Exception {
            ResolvedType index = ResolvedType.forField(JdbcRepository.class.getDeclaredField("index"),
                    UserRepository.class);

            assertEquals("Map<String, ? extends Collection<ResolvedTypeTest.User>>", index.toString());
        }

        @Test
        void resolvesReturnAndParameterTypesOfAnInheritedMethod() throws Exception {
            var findById = JdbcRepository.class.getDeclaredMethod("findById", Long.class);
            var saveAll = JdbcRepository.class.getDeclaredMethod("saveAll", List.class, Function.class);

            assertEquals(User.class, ResolvedType.forReturnType(findById, UserRepository.class).rawClass());
            assertEquals("List<? super ResolvedTypeTest.User>",
                    ResolvedType.forParameter(saveAll.getParameters()[0], UserRepository.class).toString());
            assertEquals(ResolvedType.parameterized(Function.class, User.class, String.class),
                    ResolvedType.forParameter(saveAll.getParameters()[1], UserRepository.class));
        }

        @Test
        void seenFromTheGenericClassItselfTheFieldStaysUnresolved() throws Exception {
            ResolvedType cache = field(JdbcRepository.class, "cache");

            assertFalse(cache.isFullyResolved());
            assertEquals(Entity.class, cache.typeArgument(0).rawClass());
        }

        @Test
        void doesNotBindVariablesOfAnEnclosingClass() throws Exception {
            ResolvedType value = field(Outer.Inner.class, "value");

            assertTrue(value.isUnresolvedVariable(), "documented limitation: outer type variables are not tracked");
        }
    }

    // ---- assignability ------------------------------------------------------------------

    @Nested
    class AssignabilityRules {

        @Test
        void typeArgumentsAreInvariant() throws Exception {
            ResolvedType exactNumbers = field(Fields.class, "exactNumbers");

            assertEquals(Assignability.EXACT, exactNumbers.assignabilityFrom(listOf(Number.class)));
            assertEquals(Assignability.NONE, exactNumbers.assignabilityFrom(listOf(Integer.class)),
                    "List<Integer> is not a List<Number>");
        }

        @Test
        void upperBoundedWildcardAcceptsSubtypes() throws Exception {
            ResolvedType numbers = field(Fields.class, "numbers");

            assertTrue(numbers.isAssignableFrom(listOf(Integer.class)));
            assertTrue(numbers.isAssignableFrom(listOf(Number.class)));
            assertFalse(numbers.isAssignableFrom(listOf(String.class)));
        }

        @Test
        void lowerBoundedWildcardAcceptsSupertypes() throws Exception {
            ResolvedType sinks = field(Fields.class, "integerSinks");

            assertTrue(sinks.isAssignableFrom(listOf(Integer.class)));
            assertTrue(sinks.isAssignableFrom(listOf(Number.class)));
            assertTrue(sinks.isAssignableFrom(listOf(Object.class)));
            assertFalse(sinks.isAssignableFrom(listOf(Double.class)));
        }

        @Test
        void unboundedWildcardAcceptsEverything() throws Exception {
            ResolvedType anything = field(Fields.class, "anything");

            assertEquals(Assignability.EXACT, anything.assignabilityFrom(listOf(String.class)));
            assertEquals(Assignability.EXACT, anything.assignabilityFrom(field(Fields.class, "integerSinks")));
        }

        @Test
        void wildcardCandidatesDoNotSatisfyAnExactArgument() throws Exception {
            ResolvedType exactNumbers = field(Fields.class, "exactNumbers");

            assertFalse(exactNumbers.isAssignableFrom(field(Fields.class, "numbers")),
                    "List<? extends Number> may be a List<Integer>");
        }

        @Test
        void distinguishesImplementationsByTheArgumentTheyBind() {
            ResolvedType userRepository = ResolvedType.parameterized(Repository.class, User.class);
            ResolvedType orderRepository = ResolvedType.parameterized(Repository.class, Order.class);
            ResolvedType candidate = ResolvedType.forClass(UserRepository.class);

            assertEquals(Assignability.EXACT, userRepository.assignabilityFrom(candidate));
            assertEquals(Assignability.NONE, orderRepository.assignabilityFrom(candidate));
        }

        @Test
        void comparesNestedArgumentsRecursively() throws Exception {
            ResolvedType nested = field(Fields.class, "nested");
            ResolvedType same = ResolvedType.parameterized(Map.class, ResolvedType.forClass(String.class),
                    ResolvedType.parameterized(List.class, ResolvedType.parameterized(Handler.class, User.class)));
            ResolvedType different = ResolvedType.parameterized(Map.class, ResolvedType.forClass(String.class),
                    ResolvedType.parameterized(List.class, ResolvedType.parameterized(Handler.class, Order.class)));

            assertEquals(Assignability.EXACT, nested.assignabilityFrom(same));
            assertEquals(Assignability.NONE, nested.assignabilityFrom(different));
        }

        @Test
        void rawCandidateIsAnUncheckedMatch() {
            ResolvedType strings = listOf(String.class);

            assertEquals(Assignability.UNCHECKED, strings.assignabilityFrom(ResolvedType.forClass(RawList.class)));
            assertEquals(Assignability.UNCHECKED, strings.assignabilityFrom(ResolvedType.forClass(ArrayList.class)));
        }

        @Test
        void boundOfAnUnresolvedVariableCanStillRuleACandidateOut() {
            ResolvedType candidate = ResolvedType.forClass(Box.class); // Handler<T extends Number>

            assertEquals(Assignability.UNCHECKED,
                    ResolvedType.parameterized(Handler.class, Integer.class).assignabilityFrom(candidate));
            assertEquals(Assignability.NONE,
                    ResolvedType.parameterized(Handler.class, String.class).assignabilityFrom(candidate),
                    "T extends Number can never be String");
        }

        @Test
        void nonGenericTargetOnlyNeedsTheErasure() {
            assertEquals(Assignability.EXACT,
                    ResolvedType.forClass(Entity.class).assignabilityFrom(ResolvedType.forClass(User.class)));
            assertEquals(Assignability.NONE,
                    ResolvedType.forClass(User.class).assignabilityFrom(ResolvedType.forClass(Order.class)));
        }

        @Test
        void arraysAreCovariantButGenericComponentsStillCount() throws Exception {
            assertTrue(ResolvedType.forClass(Number[].class).isAssignableFrom(Integer[].class));
            assertFalse(ResolvedType.forClass(Integer[].class).isAssignableFrom(Number[].class));
            assertFalse(ResolvedType.forClass(long[].class).isAssignableFrom(int[].class));

            ResolvedType arrayOfStringLists = field(Fields.class, "arrayOfLists");
            assertEquals("List<String>[]", arrayOfStringLists.toString());
            assertTrue(arrayOfStringLists.isAssignableFrom(arrayOfStringLists));
        }

        @Test
        void primitivesMatchTheirWrappers() throws Exception {
            ResolvedType primitive = field(Fields.class, "primitive");

            assertTrue(primitive.isAssignableFrom(Integer.class));
            assertTrue(ResolvedType.forClass(Number.class).isAssignableFrom(primitive));
            assertEquals(Integer.class, primitive.boxedRawClass());
            assertFalse(primitive.isAssignableFrom(Long.class));
        }
    }

    // ---- tokens, factories, value semantics ------------------------------------------------

    @Nested
    class TokensAndValueSemantics {

        abstract static class StringKeyed<V> extends TypeReference<Map<String, V>> {
        }

        @Test
        void typeReferenceCapturesAFullGenericType() {
            ResolvedType captured = new TypeReference<Map<String, List<User>>>() {
            }.resolved();

            assertEquals("Map<String, List<ResolvedTypeTest.User>>", captured.toString());
        }

        @Test
        void typeReferenceWorksThroughAnIntermediateSubclass() {
            ResolvedType captured = new StringKeyed<Integer>() {
            }.resolved();

            assertEquals(ResolvedType.parameterized(Map.class, String.class, Integer.class), captured);
        }

        @Test
        <T> void typeReferenceRejectsAnUnboundVariable() {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> new TypeReference<List<T>>() {
            });

            assertTrue(error.getMessage().contains("concrete type argument"), error.getMessage());
        }

        @Test
        void parameterizedChecksArity() {
            assertThrows(IllegalArgumentException.class, () -> ResolvedType.parameterized(Map.class, String.class));
        }

        @Test
        void equalTypesAreEqualAndHashAlike() throws Exception {
            ResolvedType viaField = field(Fields.class, "exactNumbers");
            ResolvedType viaFactory = listOf(Number.class);

            assertEquals(viaFactory, viaField);
            assertEquals(viaFactory.hashCode(), viaField.hashCode());
            assertNotEquals(viaFactory, field(Fields.class, "numbers"), "variance is part of identity");
        }

        @Test
        void rendersWildcards() throws Exception {
            assertEquals("List<? extends Number>", field(Fields.class, "numbers").toString());
            assertEquals("List<? super Integer>", field(Fields.class, "integerSinks").toString());
            assertEquals("List<?>", field(Fields.class, "anything").toString());
        }

        @Test
        void forInstanceHonoursResolvableTypeProvider() {
            ResolvableTypeProvider provider = () -> listOf(String.class);

            assertEquals(listOf(String.class), ResolvedType.forInstance(provider));
            assertEquals(String.class, ResolvedType.forInstance("text").rawClass());
        }
    }
}
