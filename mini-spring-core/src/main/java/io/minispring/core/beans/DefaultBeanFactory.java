package io.minispring.core.beans;

import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.env.Environment;
import io.minispring.core.type.ResolvedType;
import io.minispring.core.type.TypeReference;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The container proper: a registry of {@link BeanDefinition}s and the machinery that turns
 * them into objects on demand.
 *
 * <h2>Creation and cycles</h2>
 * Creating a bean resolves its dependencies, which recursively creates other beans. The names
 * of the beans under construction on the current thread form a stack; meeting a name that is
 * already on the stack means the dependency graph has a cycle, and the slice of the stack from
 * that name onwards <em>is</em> the cycle, ready to be printed. No "early reference" to an
 * unfinished singleton is ever handed out (see {@link CircularDependencyException} for why).
 *
 * <h2>Threading</h2>
 * Singletons live in a concurrent map, so the hot path ({@code getBean} of an existing
 * singleton) takes no lock. Creation of singletons is serialised by one re-entrant lock, which
 * makes "check, create, publish" atomic and cannot deadlock because there is only one lock.
 * Prototypes are created without it. A singleton becomes visible to other threads only after
 * every post-processor has run.
 */
public final class DefaultBeanFactory implements BeanFactory {

    private static final System.Logger LOG = System.getLogger(DefaultBeanFactory.class.getName());

    private final Map<String, BeanDefinition> definitions = new ConcurrentHashMap<>();
    private final List<BeanDefinition> definitionOrder = new CopyOnWriteArrayList<>();
    private final Map<String, Object> singletons = new ConcurrentHashMap<>();
    private final ReentrantLock singletonLock = new ReentrantLock();
    private final ThreadLocal<Deque<Frame>> creationStack = new ThreadLocal<>();
    private final List<BeanPostProcessor> postProcessors = new CopyOnWriteArrayList<>();
    private final Deque<Disposable> disposables = new ConcurrentLinkedDeque<>();
    private final List<BeanCreationRecord> creationLog = new CopyOnWriteArrayList<>();
    private final List<SkippedBean> skipped = new CopyOnWriteArrayList<>();
    private final Set<String> externalSingletons = ConcurrentHashMap.newKeySet();
    private final DependencyResolver resolver;
    private final BeanInstantiator instantiator = new BeanInstantiator(this);

    /** One bean under construction: enough to detect cycles, time it and record its edges. */
    private static final class Frame {
        private final String name;
        private final long startNanos = System.nanoTime();
        private long nestedNanos;
        private final Set<String> dependencies = new LinkedHashSet<>();

        private Frame(String name) {
            this.name = name;
        }
    }

    private record Disposable(String name, Runnable callback) {
    }

    public DefaultBeanFactory(Environment environment) {
        this.resolver = new DependencyResolver(this, environment);
    }

    // ---------------------------------------------------------------- registration

    /**
     * @throws BeanDefinitionException if the name is taken; silent overriding hides mistakes
     */
    public void registerDefinition(BeanDefinition definition) {
        BeanDefinition existing = definitions.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            throw new BeanDefinitionException("Duplicate bean name '" + definition.name() + "': defined by "
                    + existing.source() + " and by " + definition.source()
                    + ". Give one of them an explicit name");
        }
        definitionOrder.add(definition);
    }

    /** Registers an object created outside the container; it gets no lifecycle callbacks. */
    public void registerSingleton(String name, Class<?> exposedType, Object instance) {
        registerDefinition(BeanDefinition.named(name, exposedType).source("registered instance").build());
        externalSingletons.add(name);
        singletons.put(name, instance);
    }

    public void addPostProcessor(BeanPostProcessor postProcessor) {
        postProcessors.add(postProcessor);
    }

    /** Remembers a component excluded by a condition, for better "no such bean" messages. */
    public void noteSkipped(SkippedBean skippedBean) {
        skipped.add(skippedBean);
    }

    public List<SkippedBean> skippedBeans() {
        return List.copyOf(skipped);
    }

    /** All definitions in registration order. */
    public List<BeanDefinition> definitions() {
        return definitionOrder;
    }

    /** One record per bean created so far, in order of completion. */
    public List<BeanCreationRecord> creationLog() {
        return List.copyOf(creationLog);
    }

    // ---------------------------------------------------------------- lookup

    @Override
    public Object getBean(String name) {
        Object singleton = singletons.get(name);
        if (singleton != null) {
            return singleton;
        }
        BeanDefinition definition = getBeanDefinition(name);
        if (!definition.isSingleton()) {
            return createBean(definition);
        }
        singletonLock.lock();
        try {
            singleton = singletons.get(name);
            if (singleton == null) {
                singleton = createBean(definition);
                singletons.put(name, singleton);
            }
            return singleton;
        } finally {
            singletonLock.unlock();
        }
    }

    @Override
    public <T> T getBean(String name, Class<T> requiredType) {
        Object bean = getBean(name);
        Class<?> boxed = ResolvedType.forClass(requiredType).boxedRawClass();
        if (!boxed.isInstance(bean)) {
            throw new BeanNotOfRequiredTypeException(name, requiredType, bean);
        }
        @SuppressWarnings("unchecked") // checked against the (boxed) class just above
        T typed = (T) bean;
        return typed;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        return type.cast(resolver.resolveSingle(InjectionPoint.forLookup(ResolvedType.forClass(type))));
    }

    @Override
    public <T> T getBean(TypeReference<T> type) {
        @SuppressWarnings("unchecked") // the resolver matched the bean's generic type against T
        T bean = (T) resolver.resolve(InjectionPoint.forLookup(type.resolved()));
        return bean;
    }

    @Override
    public <T> Provider<T> getProvider(Class<T> type) {
        return resolver.provider(ResolvedType.forClass(type));
    }

    @Override
    public <T> Provider<T> getProvider(TypeReference<T> type) {
        return resolver.provider(type.resolved());
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        Map<String, T> beans = new LinkedHashMap<>();
        resolver.findCandidates(ResolvedType.forClass(type)).stream()
                .sorted(Comparator.comparingInt(BeanDefinition::order))
                .forEach(definition -> beans.put(definition.name(), getBean(definition.name(), type)));
        return beans;
    }

    @Override
    public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        Map<String, Object> beans = new LinkedHashMap<>();
        for (BeanDefinition definition : definitionOrder) {
            if (MergedAnnotations.isPresent(definition.beanClass(), annotationType)) {
                beans.put(definition.name(), getBean(definition.name()));
            }
        }
        return beans;
    }

    @Override
    public boolean containsBean(String name) {
        return definitions.containsKey(name);
    }

    @Override
    public List<String> getBeanNames() {
        return definitionOrder.stream().map(BeanDefinition::name).toList();
    }

    @Override
    public BeanDefinition getBeanDefinition(String name) {
        BeanDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new NoSuchBeanException("No bean named '" + name + "' is defined. Known beans: " + getBeanNames());
        }
        return definition;
    }

    /** The finished singleton, or {@code null} if it has not been created (yet); never triggers creation. */
    public Object getSingletonIfCreated(String name) {
        return singletons.get(name);
    }

    /** Resolves an injection point exactly as the container does for its own beans. */
    public Object resolveDependency(InjectionPoint point) {
        return resolver.resolve(point);
    }

    // ---------------------------------------------------------------- creation

    /** Creates every non-lazy singleton, in registration order. */
    public void preInstantiateSingletons() {
        for (BeanDefinition definition : definitionOrder) {
            if (definition.isSingleton() && !definition.isLazy()) {
                getBean(definition.name());
            }
        }
    }

    private Object createBean(BeanDefinition definition) {
        Deque<Frame> stack = creationStack.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            creationStack.set(stack);
        }
        rejectCycle(stack, definition.name());
        Frame frame = new Frame(definition.name());
        stack.addLast(frame);
        Object exposed = null;
        try {
            exposed = doCreateBean(definition);
            return exposed;
        } catch (CircularDependencyException e) {
            throw e; // already says everything; wrapping it per bean would bury the path
        } catch (RuntimeException e) {
            throw new BeanCreationException(definition, e);
        } finally {
            stack.removeLast();
            long total = System.nanoTime() - frame.startNanos;
            Frame parent = stack.peekLast();
            if (parent != null) {
                parent.nestedNanos += total;
            } else {
                creationStack.remove();
            }
            if (exposed != null) {
                creationLog.add(new BeanCreationRecord(definition.name(), definition, exposed.getClass(), total,
                        total - frame.nestedNanos, List.copyOf(frame.dependencies)));
            }
        }
    }

    private static void rejectCycle(Deque<Frame> stack, String name) {
        List<String> path = null;
        for (Frame frame : stack) {
            if (path == null && frame.name.equals(name)) {
                path = new ArrayList<>();
            }
            if (path != null) {
                path.add(frame.name);
            }
        }
        if (path != null) {
            path.add(name);
            throw new CircularDependencyException(path);
        }
    }

    private Object doCreateBean(BeanDefinition definition) {
        Object instance;
        if (definition.factoryMethod() != null) {
            instance = instantiator.invokeFactoryMethod(definition);
        } else {
            Class<?> instantiationClass = definition.beanClass();
            for (BeanPostProcessor postProcessor : postProcessors) {
                instantiationClass = postProcessor.determineInstantiationClass(definition, instantiationClass);
            }
            instance = instantiator.instantiate(definition, instantiationClass);
        }
        // A factory method may return a subclass with its own injection points and callbacks.
        Class<?> userClass = definition.factoryMethod() != null ? instance.getClass() : definition.beanClass();
        instantiator.injectMembers(instance, userClass);

        Object bean = instance;
        for (BeanPostProcessor postProcessor : postProcessors) {
            bean = requireNonNull(postProcessor.postProcessBeforeInitialization(bean, definition), postProcessor);
        }
        instantiator.invokeInitCallbacks(instance, userClass, definition);
        for (BeanPostProcessor postProcessor : postProcessors) {
            bean = requireNonNull(postProcessor.postProcessAfterInitialization(bean, definition), postProcessor);
        }
        if (definition.isSingleton()) {
            // Destroy callbacks target the raw instance: a wrapper must not intercept shutdown.
            Runnable callback = instantiator.destroyCallback(instance, userClass, definition);
            if (callback != null) {
                disposables.addLast(new Disposable(definition.name(), callback));
            }
        }
        return bean;
    }

    private static Object requireNonNull(Object bean, BeanPostProcessor postProcessor) {
        if (bean == null) {
            throw new BeansException(postProcessor.getClass().getName() + " returned null instead of a bean");
        }
        return bean;
    }

    /** Name of the bean being created on this thread, or {@code null}. */
    String currentlyCreating() {
        Deque<Frame> stack = creationStack.get();
        return stack == null || stack.isEmpty() ? null : stack.peekLast().name;
    }

    /** Notes that the bean being created on this thread received {@code dependencyName}. */
    void recordDependency(String dependencyName) {
        Deque<Frame> stack = creationStack.get();
        if (stack != null && !stack.isEmpty() && !stack.peekLast().name.equals(dependencyName)) {
            stack.peekLast().dependencies.add(dependencyName);
        }
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Runs destroy callbacks in reverse order of creation. A bean finishes creation only after
     * its dependencies did, so the reverse order tears dependents down before what they use.
     * A failing callback is logged and does not stop the others.
     */
    public void destroySingletons() {
        singletonLock.lock();
        try {
            Disposable disposable;
            while ((disposable = disposables.pollLast()) != null) {
                try {
                    disposable.callback().run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Destroying bean ''{0}'' failed: {1}", disposable.name(),
                            e.getMessage());
                    LOG.log(System.Logger.Level.DEBUG, "Destroy callback failure", e);
                }
            }
            singletons.keySet().removeIf(name -> !externalSingletons.contains(name));
        } finally {
            singletonLock.unlock();
        }
    }
}
