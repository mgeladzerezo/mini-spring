package io.minispring.core.context;

/**
 * Entry point for an application: {@code MiniApplication.run(App.class, args)}.
 *
 * <p>It registers the given class (so its {@code @Enable...} and {@code @Import} annotations take
 * effect), scans the class's package, applies {@code --key=value} arguments as the
 * highest-priority properties, starts everything and prints the {@link StartupReport}.
 */
public final class MiniApplication {

    private MiniApplication() {
    }

    /**
     * @param primarySource the application class; its package is the scan root
     * @param args          program arguments; {@code --key=value} pairs become properties
     * @return the running context; closing it (or JVM shutdown) stops the application
     */
    public static ApplicationContext run(Class<?> primarySource, String... args) {
        ApplicationContext context = ApplicationContext.builder()
                .register(primarySource)
                .scan(primarySource.getPackageName())
                .args(args)
                .registerShutdownHook(true)
                .build();
        if (context.environment().getProperty("mini.startup-report", Boolean.class, true)) {
            System.out.println(context.startupReport().render());
        }
        return context;
    }
}
