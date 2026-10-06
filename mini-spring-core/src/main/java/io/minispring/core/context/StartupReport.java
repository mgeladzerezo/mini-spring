package io.minispring.core.context;

import io.minispring.core.beans.BeanCreationRecord;
import io.minispring.core.beans.SkippedBean;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What happened during startup: how long each phase took, which beans were created, what each
 * one depends on and how long its own construction took.
 *
 * <p>The dependency edges are not reconstructed from annotations afterwards; they are recorded
 * by the container at the moment a dependency is actually resolved, so the graph shows what was
 * really injected (including the members of a {@code List<T>}).
 */
public final class StartupReport {

    /** A named slice of startup time. */
    public record Phase(String name, long nanos) {
    }

    private final List<Phase> phases;
    private final List<BeanCreationRecord> beans;
    private final List<SkippedBean> skipped;
    private final long totalNanos;
    private final int scannedClasses;

    StartupReport(List<Phase> phases, List<BeanCreationRecord> beans, List<SkippedBean> skipped, long totalNanos,
                  int scannedClasses) {
        this.phases = List.copyOf(phases);
        this.beans = List.copyOf(beans);
        this.skipped = List.copyOf(skipped);
        this.totalNanos = totalNanos;
        this.scannedClasses = scannedClasses;
    }

    public Duration total() {
        return Duration.ofNanos(totalNanos);
    }

    public List<Phase> phases() {
        return phases;
    }

    /** Creation records in the order beans finished construction. */
    public List<BeanCreationRecord> beans() {
        return beans;
    }

    public List<SkippedBean> skipped() {
        return skipped;
    }

    public int scannedClasses() {
        return scannedClasses;
    }

    /** Renders the report as plain text: a summary line, the phases and the bean graph as a tree. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "mini-spring started in %s (%d beans created, %d classes scanned)%n",
                millis(totalNanos), beans.size(), scannedClasses));
        out.append("  phases:");
        for (Phase phase : phases) {
            out.append(' ').append(phase.name()).append(' ').append(millis(phase.nanos())).append(" |");
        }
        out.setLength(out.length() - 2);
        out.append(System.lineSeparator());
        out.append("  bean graph (a bean's dependencies are indented below it; time excludes dependencies):")
                .append(System.lineSeparator());

        Map<String, BeanCreationRecord> byName = new LinkedHashMap<>();
        beans.forEach(bean -> byName.put(bean.name(), bean));
        Set<String> dependedUpon = new HashSet<>();
        beans.forEach(bean -> dependedUpon.addAll(bean.dependencies()));
        Set<String> printed = new HashSet<>();
        for (BeanCreationRecord bean : beans) {
            if (!dependedUpon.contains(bean.name())) {
                renderNode(bean, byName, printed, "  ", "", out);
            }
        }
        for (SkippedBean skippedBean : skipped) {
            out.append("  skipped: ").append(skippedBean.name()).append(" - ").append(skippedBean.reason())
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    private static void renderNode(BeanCreationRecord bean, Map<String, BeanCreationRecord> byName,
                                   Set<String> printed, String indent, String connector, StringBuilder out) {
        boolean first = printed.add(bean.name());
        String label = indent + connector + bean.name();
        if (!first) {
            out.append(label).append(" (shown above)").append(System.lineSeparator());
            return;
        }
        out.append(String.format(Locale.ROOT, "%-46s %-44s %9s%n", label, describeType(bean), millis(bean.selfNanos())));
        String childIndent = indent + (connector.isEmpty() ? "" : connector.startsWith("`") ? "   " : "|  ");
        List<BeanCreationRecord> children = bean.dependencies().stream()
                .map(byName::get)
                .filter(java.util.Objects::nonNull) // registered instances have no creation record
                .toList();
        for (int i = 0; i < children.size(); i++) {
            renderNode(children.get(i), byName, printed, childIndent, i == children.size() - 1 ? "`- " : "+- ", out);
        }
    }

    private static String describeType(BeanCreationRecord bean) {
        String type = bean.definition().type().toString();
        Class<?> exposed = bean.exposedClass();
        if (Proxy.isProxyClass(exposed)) {
            return type + " [JDK proxy]";
        }
        if (exposed.isHidden()) {
            return type + " [subclass proxy]";
        }
        return type;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos / 1_000_000.0);
    }
}
