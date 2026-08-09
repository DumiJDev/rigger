package io.rigger.operator.reconcile;

import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.ReconciliationEvent;
import io.rigger.operator.controller.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * The central reconciliation loop.
 *
 * <p>Runs every 15 seconds (configurable via {@code rigger.operator.reconcile-interval-seconds}).
 * Uses a structured scope to run all controllers concurrently via Virtual Threads,
 * with a shared shutdown policy if any controller throws.
 *
 * <p>Each cycle:
 * <ol>
 *   <li>Runs DeploymentController, ServiceController, ConfigMapController in parallel</li>
 *   <li>Records the cycle result (changes, errors, duration)</li>
 *   <li>Publishes a ReconciliationEvent for the UI and audit log</li>
 * </ol>
 *
 * <p>Errors in individual controllers are caught and logged — one failing controller
 * does not abort the entire cycle.
 */
@Component
public class ReconciliationLoop {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLoop.class);

    private final DeploymentController deploymentCtrl;
    private final ServiceController    serviceCtrl;
    private final ConfigMapController  configMapCtrl;
    private final RiggerEventBus       eventBus;

    public ReconciliationLoop(DeploymentController deploymentCtrl,
                               ServiceController    serviceCtrl,
                               ConfigMapController  configMapCtrl,
                               RiggerEventBus       eventBus) {
        this.deploymentCtrl = deploymentCtrl;
        this.serviceCtrl    = serviceCtrl;
        this.configMapCtrl  = configMapCtrl;
        this.eventBus       = eventBus;
    }

    @Scheduled(fixedDelayString = "${rigger.operator.reconcile-interval-seconds:15}000")
    public void reconcile() {
        var start = Instant.now();
        log.debug("Reconciliation cycle starting...");

        int totalChanges = 0;
        int errors = 0;

        // Run controllers — each catches its own exceptions internally
        // Virtual Threads used here so each controller can block on I/O concurrently
        try (var scope = new java.util.concurrent.StructuredTaskScope.ShutdownOnFailure()) {
            var deployFuture = scope.fork(() -> runController("Deployment", deploymentCtrl::reconcile));
            var svcFuture    = scope.fork(() -> runController("Service",    serviceCtrl::reconcile));
            var cfgFuture    = scope.fork(() -> runController("ConfigMap",  configMapCtrl::reconcile));

            scope.join();
            // Collect results — individual failures are already logged inside runController
            totalChanges += deployFuture.get();
            totalChanges += svcFuture.get();
            totalChanges += cfgFuture.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Reconciliation cycle interrupted");
            errors++;
        } catch (Exception e) {
            log.error("Unexpected error in reconciliation cycle", e);
            errors++;
        }

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        if (totalChanges > 0 || errors > 0) {
            log.info("Reconciliation complete: changes={} errors={} duration={}ms",
                totalChanges, errors, durationMs);
        } else {
            log.debug("Reconciliation complete: no changes ({}ms)", durationMs);
        }

        eventBus.publish(new ReconciliationEvent(0, totalChanges, 0, errors, durationMs));
    }

    private int runController(String name, java.util.concurrent.Callable<Integer> ctrl) {
        try {
            return ctrl.call();
        } catch (Exception e) {
            log.error("Controller {} threw: {}", name, e.getMessage(), e);
            return 0;
        }
    }
}
