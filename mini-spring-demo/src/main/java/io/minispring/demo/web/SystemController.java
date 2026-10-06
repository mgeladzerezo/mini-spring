package io.minispring.demo.web;

import io.minispring.aop.aspects.MethodTimings;
import io.minispring.core.beans.BeanCreationRecord;
import io.minispring.core.context.ApplicationContext;
import io.minispring.core.context.StartupReport;
import io.minispring.demo.service.TransferService;
import io.minispring.web.annotation.GetMapping;
import io.minispring.web.annotation.RequestMapping;
import io.minispring.web.annotation.RestController;
import io.minispring.web.mvc.DispatcherHandler;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Introspection endpoints for the UI: what the container built, how fast, and which routes exist. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    public record BeanInfo(String name, String type, String scope, double millis, List<String> dependencies) {
    }

    private final ApplicationContext context;
    private final DispatcherHandler dispatcher;
    private final MethodTimings timings;
    private final TransferService transferService;

    public SystemController(ApplicationContext context, DispatcherHandler dispatcher, MethodTimings timings,
                            TransferService transferService) {
        this.context = context;
        this.dispatcher = dispatcher;
        this.timings = timings;
        this.transferService = transferService;
    }

    @GetMapping
    public Map<String, Object> system() {
        StartupReport report = context.startupReport();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("startupMillis", report.total().toNanos() / 1_000_000.0);
        info.put("scannedClasses", report.scannedClasses());
        Map<String, Double> phases = new LinkedHashMap<>();
        report.phases().forEach(phase -> phases.put(phase.name(), phase.nanos() / 1_000_000.0));
        info.put("phases", phases);
        info.put("beans", report.beans().stream().map(SystemController::describe).toList());
        info.put("routes", dispatcher.describeRoutes());
        info.put("timings", timings.snapshot());
        info.put("totalBalance", totalBalance());
        return info;
    }

    private BigDecimal totalBalance() {
        return transferService.totalBalance();
    }

    private static BeanInfo describe(BeanCreationRecord bean) {
        return new BeanInfo(bean.name(), bean.exposedClass().getSimpleName(), bean.scope().name(),
                bean.totalNanos() / 1_000_000.0, List.copyOf(bean.dependencies()));
    }
}
