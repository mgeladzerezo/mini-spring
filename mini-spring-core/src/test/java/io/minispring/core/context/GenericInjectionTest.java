package io.minispring.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Autowired;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.Order;
import io.minispring.core.beans.BeanCreationException;
import io.minispring.core.beans.NoSuchBeanException;
import io.minispring.core.beans.NoUniqueBeanException;
import io.minispring.core.beans.Provider;
import io.minispring.core.type.TypeReference;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves that injection matches on full generic types: the same raw interface resolves to
 * different beans depending on the type argument, and collections filter by it.
 */
class GenericInjectionTest {

    interface Entity {
    }

    record User(String name) implements Entity {
    }

    record Purchase(int number) implements Entity {
    }

    record Invoice(int number) implements Entity {
    }

    record Payment(int cents) implements Entity {
    }

    interface Repository<T> {
        Class<T> entityType();
    }

    abstract static class InMemoryRepository<T> implements Repository<T> {
    }

    @Component
    static class UserRepository extends InMemoryRepository<User> {
        @Override
        public Class<User> entityType() {
            return User.class;
        }
    }

    @Component
    static class OrderRepository extends InMemoryRepository<Purchase> {
        @Override
        public Class<Purchase> entityType() {
            return Purchase.class;
        }
    }

    static class AdHocRepository<T> implements Repository<T> {
        private final Class<T> type;

        AdHocRepository(Class<T> type) {
            this.type = type;
        }

        @Override
        public Class<T> entityType() {
            return type;
        }
    }

    // ---- one interface, several parameterisations ------------------------------------------

    @Component
    static class Reports {
        final Repository<User> users;
        final Repository<Purchase> orders;

        Reports(Repository<User> users, Repository<Purchase> orders) {
            this.users = users;
            this.orders = orders;
        }
    }

    @Test
    void picksTheImplementationWhoseHierarchyBindsTheRequestedArgument() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class, OrderRepository.class,
                Reports.class)) {
            Reports reports = context.getBean(Reports.class);

            assertInstanceOf(UserRepository.class, reports.users);
            assertInstanceOf(OrderRepository.class, reports.orders);
        }
    }

    abstract static class CrudService<T extends Entity> {
        @Autowired
        Repository<T> repository;

        Repository<T> viaSetter;

        @Autowired
        void setRepository(Repository<T> repository) {
            this.viaSetter = repository;
        }
    }

    @Component
    static class UserService extends CrudService<User> {
    }

    @Component
    static class OrderService extends CrudService<Purchase> {
    }

    @Test
    void resolvesAnInjectionPointDeclaredInAGenericSuperclassPerSubclass() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class, OrderRepository.class,
                UserService.class, OrderService.class)) {
            assertInstanceOf(UserRepository.class, context.getBean(UserService.class).repository);
            assertInstanceOf(UserRepository.class, context.getBean(UserService.class).viaSetter);
            assertInstanceOf(OrderRepository.class, context.getBean(OrderService.class).repository);
        }
    }

    @Configuration
    static class FactoryMethods {
        @Bean
        Repository<Invoice> invoices() {
            return new AdHocRepository<>(Invoice.class);
        }

        @Bean
        Repository<Payment> payments() {
            return new AdHocRepository<>(Payment.class);
        }
    }

    @Component
    static class Billing {
        final Repository<Invoice> invoices;
        final Repository<Payment> payments;

        Billing(Repository<Payment> payments, Repository<Invoice> invoices) {
            this.invoices = invoices;
            this.payments = payments;
        }
    }

    @Test
    void factoryMethodsAreMatchedByTheirDeclaredGenericReturnType() {
        try (ApplicationContext context = new ApplicationContext(FactoryMethods.class, Billing.class)) {
            Billing billing = context.getBean(Billing.class);

            // Both objects are AdHocRepository instances; only the declarations tell them apart.
            assertEquals(Invoice.class, billing.invoices.entityType());
            assertEquals(Payment.class, billing.payments.entityType());
        }
    }

    abstract static class RepositoryConfig<T> {
        abstract Class<T> type();

        @Bean
        Repository<T> repository() {
            return new AdHocRepository<>(type());
        }
    }

    @Configuration
    static class InvoiceConfig extends RepositoryConfig<Invoice> {
        @Override
        Class<Invoice> type() {
            return Invoice.class;
        }
    }

    @Test
    void anInheritedFactoryMethodIsTypedFromTheConcreteConfigurationClass() {
        try (ApplicationContext context = new ApplicationContext(InvoiceConfig.class)) {
            Repository<Invoice> repository = context.getBean(new TypeReference<Repository<Invoice>>() {
            });

            assertEquals(Invoice.class, repository.entityType());
            assertEquals("GenericInjectionTest.Repository<GenericInjectionTest.Invoice>",
                    context.getBeanDefinition("repository").type().toString());
        }
    }

    // ---- lookups by generic type -------------------------------------------------------------

    @Test
    void typeReferenceLookupsDistinguishParameterisations() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class, OrderRepository.class)) {
            Repository<User> users = context.getBean(new TypeReference<Repository<User>>() {
            });
            Repository<Purchase> orders = context.getBean(new TypeReference<Repository<Purchase>>() {
            });

            assertEquals(User.class, users.entityType());
            assertEquals(Purchase.class, orders.entityType());
            assertThrows(NoUniqueBeanException.class, () -> context.getBean(Repository.class));
            assertThrows(NoSuchBeanException.class, () -> context.getBean(new TypeReference<Repository<Invoice>>() {
            }));
        }
    }

    @Test
    void aMissingParameterisationIsExplainedByListingTheOnesThatExist() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class)) {
            NoSuchBeanException error = assertThrows(NoSuchBeanException.class,
                    () -> context.getBean(new TypeReference<Repository<Invoice>>() {
                    }));

            assertTrue(error.getMessage().contains("No bean of type GenericInjectionTest.Repository<GenericInjectionTest.Invoice>"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("with other type arguments: 'userRepository'"), error.getMessage());
        }
    }

    @Component
    static class WildcardConsumer {
        @Autowired
        List<Repository<? extends Entity>> all;
        @Autowired
        Repository<? extends User> users;
    }

    @Test
    void wildcardsMatchByBound() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class, OrderRepository.class,
                WildcardConsumer.class)) {
            WildcardConsumer consumer = context.getBean(WildcardConsumer.class);

            assertEquals(2, consumer.all.size());
            assertInstanceOf(UserRepository.class, consumer.users);
        }
    }

    // ---- unchecked fallback ------------------------------------------------------------------

    @Component
    @SuppressWarnings("rawtypes")
    static class LegacyRepository implements Repository {
        @Override
        public Class entityType() {
            return Object.class;
        }
    }

    @Component
    static class NeedsInvoices {
        final Repository<Invoice> invoices;

        NeedsInvoices(Repository<Invoice> invoices) {
            this.invoices = invoices;
        }
    }

    @Component
    static class NeedsUsers {
        final Repository<User> users;

        NeedsUsers(Repository<User> users) {
            this.users = users;
        }
    }

    @Test
    void aRawBeanIsUsedOnlyWhenNoPreciselyTypedBeanExists() {
        try (ApplicationContext context = new ApplicationContext(LegacyRepository.class, UserRepository.class,
                NeedsInvoices.class, NeedsUsers.class)) {
            assertInstanceOf(LegacyRepository.class, context.getBean(NeedsInvoices.class).invoices,
                    "no Repository<Invoice> is declared, so the raw one is the unchecked fallback");
            assertInstanceOf(UserRepository.class, context.getBean(NeedsUsers.class).users,
                    "an exact match shadows the raw bean instead of being ambiguous with it");
        }
    }

    // ---- collections ------------------------------------------------------------------------

    interface Handler<T> {
        String name();
    }

    @Component
    @Order(2)
    static class TrimHandler implements Handler<String> {
        @Override
        public String name() {
            return "trim";
        }
    }

    @Component
    @Order(1)
    static class UpperHandler implements Handler<String> {
        @Override
        public String name() {
            return "upper";
        }
    }

    @Component
    @Order(0)
    static class RoundHandler implements Handler<Double> {
        @Override
        public String name() {
            return "round";
        }
    }

    @Component
    static class Pipeline {
        final List<Handler<String>> stringHandlers;
        final List<Handler<?>> allHandlers;
        final Map<String, Handler<String>> byName;
        final Set<Handler<Double>> doubleHandlers;
        final Handler<?>[] asArray;
        final Collection<Handler<Integer>> none;

        Pipeline(List<Handler<String>> stringHandlers, List<Handler<?>> allHandlers,
                 Map<String, Handler<String>> byName, Set<Handler<Double>> doubleHandlers, Handler<?>[] asArray,
                 Collection<Handler<Integer>> none) {
            this.stringHandlers = stringHandlers;
            this.allHandlers = allHandlers;
            this.byName = byName;
            this.doubleHandlers = doubleHandlers;
            this.asArray = asArray;
            this.none = none;
        }
    }

    @Test
    void collectionsFilterByElementTypeArgumentAndFollowOrder() {
        try (ApplicationContext context = new ApplicationContext(TrimHandler.class, UpperHandler.class,
                RoundHandler.class, Pipeline.class)) {
            Pipeline pipeline = context.getBean(Pipeline.class);

            assertEquals(List.of("upper", "trim"), pipeline.stringHandlers.stream().map(Handler::name).toList());
            assertEquals(List.of("round", "upper", "trim"), pipeline.allHandlers.stream().map(Handler::name).toList());
            assertEquals(List.of("upperHandler", "trimHandler"), List.copyOf(pipeline.byName.keySet()));
            assertEquals(1, pipeline.doubleHandlers.size());
            assertEquals(3, pipeline.asArray.length);
            assertTrue(pipeline.none.isEmpty(), "no matching bean yields an empty collection, not an error");
            assertThrows(UnsupportedOperationException.class, () -> pipeline.stringHandlers.add(null));
        }
    }

    @Test
    void typeReferenceLookupCanAskForACollection() {
        try (ApplicationContext context = new ApplicationContext(TrimHandler.class, UpperHandler.class,
                RoundHandler.class)) {
            List<Handler<String>> handlers = context.getBean(new TypeReference<List<Handler<String>>>() {
            });

            assertEquals(2, handlers.size());
            assertEquals(List.of("upperHandler", "trimHandler"),
                    List.copyOf(context.getBeansOfType(Handler.class).keySet()).subList(1, 3));
        }
    }

    @Component
    static class CompositeHandler implements Handler<String> {
        final List<Handler<String>> delegates;

        CompositeHandler(List<Handler<String>> delegates) {
            this.delegates = delegates;
        }

        @Override
        public String name() {
            return "composite";
        }
    }

    @Test
    void aCompositeReceivesItsSiblingsButNotItself() {
        try (ApplicationContext context = new ApplicationContext(CompositeHandler.class, TrimHandler.class,
                UpperHandler.class)) {
            CompositeHandler composite = context.getBean(CompositeHandler.class);

            assertEquals(List.of("upper", "trim"), composite.delegates.stream().map(Handler::name).toList());
        }
    }

    @Configuration
    static class DirectCollectionBean {
        @Bean
        List<String> allowedHosts() {
            return List.of("a.example", "b.example");
        }
    }

    @Component
    static class HostChecker {
        final List<String> hosts;

        HostChecker(List<String> hosts) {
            this.hosts = hosts;
        }
    }

    @Test
    void aBeanThatIsItselfACollectionIsInjectedWhenNoElementBeansExist() {
        try (ApplicationContext context = new ApplicationContext(DirectCollectionBean.class, HostChecker.class)) {
            assertEquals(List.of("a.example", "b.example"), context.getBean(HostChecker.class).hosts);
        }
    }

    // ---- Optional and Provider -----------------------------------------------------------------

    @Component
    static class OptionalConsumer {
        final Optional<Repository<User>> users;
        final Optional<Repository<Invoice>> invoices;
        final Provider<Repository<Purchase>> orders;
        final Provider<Repository<Invoice>> missing;
        final Provider<Repository<? extends Entity>> ambiguous;

        OptionalConsumer(Optional<Repository<User>> users, Optional<Repository<Invoice>> invoices,
                         Provider<Repository<Purchase>> orders, Provider<Repository<Invoice>> missing,
                         Provider<Repository<? extends Entity>> ambiguous) {
            this.users = users;
            this.invoices = invoices;
            this.orders = orders;
            this.missing = missing;
            this.ambiguous = ambiguous;
        }
    }

    @Test
    void optionalAndProviderResolveTheirTypeArgument() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class, OrderRepository.class,
                OptionalConsumer.class)) {
            OptionalConsumer consumer = context.getBean(OptionalConsumer.class);

            assertInstanceOf(UserRepository.class, consumer.users.orElseThrow());
            assertTrue(consumer.invoices.isEmpty());
            assertSame(context.getBean(OrderRepository.class), consumer.orders.get());
            assertTrue(consumer.missing.getIfAvailable().isEmpty());
            assertThrows(NoSuchBeanException.class, consumer.missing::get);
            assertTrue(consumer.ambiguous.getIfAvailable().isEmpty(), "two candidates, none preferred");
            assertThrows(NoUniqueBeanException.class, consumer.ambiguous::get);
            assertEquals(2, consumer.ambiguous.stream().count());
        }
    }

    @Test
    void programmaticProvidersAreLazyToo() {
        try (ApplicationContext context = new ApplicationContext(UserRepository.class)) {
            Provider<Repository<User>> provider = context.getProvider(new TypeReference<>() {
            });

            assertSame(context.getBean(UserRepository.class), provider.get());
            assertNotSame(provider, context.getProvider(UserRepository.class));
            assertEquals("Provider<GenericInjectionTest.Repository<GenericInjectionTest.User>>", provider.toString());
        }
    }

    @Test
    void anAmbiguousGenericInjectionListsTheCandidates() {
        BeanCreationException error = assertThrows(BeanCreationException.class,
                () -> new ApplicationContext(UserRepository.class, OrderRepository.class, AmbiguousConsumer.class));

        assertTrue(error.getMessage().contains("but found 2: 'userRepository'"), error.getMessage());
        assertTrue(error.getMessage().contains("'orderRepository'"), error.getMessage());
        assertInstanceOf(NoUniqueBeanException.class, error.rootCause());
        assertEquals(List.of("userRepository", "orderRepository"),
                ((NoUniqueBeanException) error.rootCause()).candidates());
    }

    @Component
    static class AmbiguousConsumer {
        AmbiguousConsumer(Repository<? extends Entity> any) {
        }
    }
}
