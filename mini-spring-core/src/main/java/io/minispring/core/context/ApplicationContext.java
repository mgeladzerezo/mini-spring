package io.minispring.core.context;

import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanFactory;
import io.minispring.core.beans.BeanPostProcessor;
import io.minispring.core.beans.DefaultBeanFactory;
import io.minispring.core.beans.Provider;
import io.minispring.core.condition.ConditionContext;
import io.minispring.core.convert.ConversionService;
import io.minispring.core.env.Environment;
import io.minispring.core.env.MapPropertySource;
import io.minispring.core.env.SystemEnvironmentPropertySource;
import io.minispring.core.event.ApplicationEventPublisher;
import io.minispring.core.event.EventListenerRegistrar;
import io.minispring.core.event.EventMulticaster;
import io.minispring.core.type.TypeReference;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A running application: configuration, the bean container, events and lifecycle in one object.
 *
 * <p>Startup ("refresh") happens in the constructor, in fixed phases:
 * <ol>
 *   <li>build the {@link Environment} from property sources;</li>
 *   <li>read bean definitions from the registered classes and scanned packages;</li>
 *   <li>create {@link BeanPostProcessor} beans first, so they can act on everything after them;</li>
 *   <li>create all non-lazy singletons;</li>
 *   <li>start {@link Lifecycle} beans and publish {@link ContextRefreshedEvent}.</li>
 * </ol>
 * If any phase fails, the beans created so far are destroyed and the exception propagates:
 * a context is either fully started or not there at all.
 */
public final class ApplicationContext implements BeanFactory, ApplicationEventPublisher, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ApplicationContext.class.getName());

    private enum State {
        ACTIVE, CLOSING, CLOSED
    }

    private final Environment environment;
    private final DefaultBeanFactory beanFactory;
    private final EventMulticaster multicaster = new EventMulticaster();
    private final List<Lifecycle> runningLifecycles = new ArrayList<>();
    private final StartupReport startupReport;
    private final Object closeMonitor = new Object();
    private volatile State state = State.ACTIVE;
    private Thread shutdownHook;

    /** Creates and starts a context from explicitly listed component and configuration classes. */
    public ApplicationContext(Class<?>... componentClasses) {
        this(builder().register(componentClasses));
    }

    private ApplicationContext(Builder builder) {
        long start = System.nanoTime();
        this.environment = buildEnvironment(builder);
        this.beanFactory = new DefaultBeanFactory(environment);
        List<StartupReport.Phase> phases = new ArrayList<>();
        StartupReport report;
        try {
            int scannedClasses = refresh(builder, phases);
            report = new StartupReport(phases, beanFactory.creationLog(), beanFactory.skippedBeans(),
                    System.nanoTime() - start, scannedClasses);
        } catch (RuntimeException | Error e) {
            state = State.CLOSING;
            stopLifecycles();
            beanFactory.destroySingletons();
            state = State.CLOSED;
            throw e;
        }
        this.startupReport = report;
        try {
            publishEvent(new ContextRefreshedEvent(this));
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
        if (builder.shutdownHook) {
            shutdownHook = new Thread(this::close, "mini-spring-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Environment buildEnvironment(Builder builder) {
        Environment environment = new Environment(new ConversionService());
        environment.addLast(MapPropertySource.fromCommandLine(builder.args));
        environment.addLast(new MapPropertySource("programmatic", builder.properties));
        environment.addLast(MapPropertySource.systemProperties());
        environment.addLast(new SystemEnvironmentPropertySource());
        environment.addActiveProfiles(builder.profiles.toArray(String[]::new));
        // Profiles must be known before profile-specific files are added; those files rank above the base file.
        for (String profile : environment.activeProfiles()) {
            MapPropertySource.fromClasspath("application-" + profile + ".properties", builder.classLoader)
                    .ifPresent(environment::addLast);
        }
        MapPropertySource.fromClasspath("application.properties", builder.classLoader).ifPresent(environment::addLast);
        return environment;
    }

    private int refresh(Builder builder, List<StartupReport.Phase> phases) {
        long mark = System.nanoTime();
        beanFactory.registerSingleton("environment", Environment.class, environment);
        beanFactory.registerSingleton("conversionService", ConversionService.class, environment.conversionService());
        beanFactory.registerSingleton("applicationContext", ApplicationContext.class, this);
        builder.instances.forEach((name, instance) -> beanFactory.registerSingleton(name, instance.getClass(), instance));

        BeanDefinitionReader reader = new BeanDefinitionReader(beanFactory,
                new ConditionContext(environment, builder.classLoader));
        builder.componentClasses.forEach(reader::register);
        builder.basePackages.forEach(reader::scan);
        mark = phase(phases, "definitions", mark);

        // Registered before any bean exists so that no listener is missed, however early its bean is created.
        beanFactory.addPostProcessor(new EventListenerRegistrar(beanFactory, multicaster));
        beanFactory.definitions().stream()
                .filter(definition -> BeanPostProcessor.class.isAssignableFrom(definition.type().rawClass()))
                .sorted(Comparator.comparingInt(BeanDefinition::order))
                .forEach(definition -> beanFactory.addPostProcessor(
                        beanFactory.getBean(definition.name(), BeanPostProcessor.class)));
        mark = phase(phases, "post-processors", mark);

        beanFactory.preInstantiateSingletons();
        mark = phase(phases, "singletons", mark);

        beanFactory.definitions().stream()
                .filter(definition -> definition.isSingleton() && !definition.isLazy()
                        && Lifecycle.class.isAssignableFrom(definition.type().rawClass()))
                .sorted(Comparator.comparingInt(BeanDefinition::order))
                .forEach(definition -> {
                    Lifecycle lifecycle = beanFactory.getBean(definition.name(), Lifecycle.class);
                    lifecycle.start();
                    runningLifecycles.add(lifecycle);
                });
        phase(phases, "lifecycle", mark);
        return reader.scannedClassCount();
    }

    private static long phase(List<StartupReport.Phase> phases, String name, long startedAt) {
        long now = System.nanoTime();
        phases.add(new StartupReport.Phase(name, now - startedAt));
        return now;
    }

    // ---------------------------------------------------------------- accessors

    public Environment environment() {
        return environment;
    }

    /** Timings and the bean graph recorded during startup. */
    public StartupReport startupReport() {
        return startupReport;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    @Override
    public void publishEvent(Object event) {
        multicaster.publishEvent(event);
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Stops the application: publishes {@link ContextClosedEvent}, stops {@link Lifecycle} beans
     * in reverse start order, then destroys singletons in reverse creation order. Idempotent.
     */
    @Override
    public void close() {
        synchronized (closeMonitor) {
            if (state != State.ACTIVE) {
                return;
            }
            state = State.CLOSING;
            try {
                publishEvent(new ContextClosedEvent(this));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A ContextClosedEvent listener failed", e);
            }
            stopLifecycles();
            beanFactory.destroySingletons();
            state = State.CLOSED;
            if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException alreadyShuttingDown) {
                    // the JVM is going down anyway; the hook will find the context closed
                }
            }
        }
    }

    private void stopLifecycles() {
        for (Lifecycle lifecycle : runningLifecycles.reversed()) {
            try {
                lifecycle.stop();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Stopping " + lifecycle.getClass().getName() + " failed", e);
            }
        }
        runningLifecycles.clear();
    }

    private DefaultBeanFactory factory() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("The application context has been closed");
        }
        return beanFactory;
    }

    // ---------------------------------------------------------------- BeanFactory

    @Override
    public Object getBean(String name) {
        return factory().getBean(name);
    }

    @Override
    public <T> T getBean(String name, Class<T> requiredType) {
        return factory().getBean(name, requiredType);
    }

    @Override
    public <T> T getBean(Class<T> type) {
        return factory().getBean(type);
    }

    @Override
    public <T> T getBean(TypeReference<T> type) {
        return factory().getBean(type);
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return factory().getProvider(type);
    }

    @Override
    public <T> Provider<T> getProvider(TypeReference<T> type) {
        return factory().getProvider(type);
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        return factory().getBeansOfType(type);
    }

    @Override
    public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return factory().getBeansWithAnnotation(annotationType);
    }

    @Override
    public boolean containsBean(String name) {
        return beanFactory.containsBean(name);
    }

    @Override
    public List<String> getBeanNames() {
        return beanFactory.getBeanNames();
    }

    @Override
    public BeanDefinition getBeanDefinition(String name) {
        return beanFactory.getBeanDefinition(name);
    }

    /** Collects what to start; {@link #build()} creates and refreshes the context. */
    public static final class Builder {

        private final Set<Class<?>> componentClasses = new LinkedHashSet<>();
        private final Set<String> basePackages = new LinkedHashSet<>();
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Object> instances = new LinkedHashMap<>();
        private final List<String> profiles = new ArrayList<>();
        private String[] args = {};
        private ClassLoader classLoader = Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : ApplicationContext.class.getClassLoader();
        private boolean shutdownHook;

        private Builder() {
        }

        /** Adds component or configuration classes; they need no stereotype annotation. */
        public Builder register(Class<?>... classes) {
            componentClasses.addAll(Arrays.asList(classes));
            return this;
        }

        /** Adds packages to scan for {@code @Component} classes. */
        public Builder scan(String... packages) {
            basePackages.addAll(Arrays.asList(packages));
            return this;
        }

        /** Sets a property that overrides system properties, environment and property files. */
        public Builder property(String key, String value) {
            properties.put(key, value);
            return this;
        }

        public Builder profiles(String... activeProfiles) {
            profiles.addAll(Arrays.asList(activeProfiles));
            return this;
        }

        /** Program arguments; {@code --key=value} pairs get the highest precedence. */
        public Builder args(String... programArguments) {
            this.args = programArguments.clone();
            return this;
        }

        /** Registers an existing object as a singleton bean, e.g. a test double. */
        public Builder singleton(String name, Object instance) {
            instances.put(name, instance);
            return this;
        }

        /** The loader used for scanning and for {@code application.properties}. */
        public Builder classLoader(ClassLoader loader) {
            this.classLoader = loader;
            return this;
        }

        /** Whether to close the context when the JVM exits. */
        public Builder registerShutdownHook(boolean register) {
            this.shutdownHook = register;
            return this;
        }

        public ApplicationContext build() {
            return new ApplicationContext(this);
        }
    }
}
