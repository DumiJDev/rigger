package io.rigger.operator.reconcile;

import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.ReconciliationEvent;
import io.rigger.operator.controller.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * The central reconciliation loop.
 *
 * <p>Runs every 15 seconds (configurable via {@code rigger.operator.reconcile-interval-seconds}).
 * Runs all controllers concurrently on virtual threads, one per controller, and waits for the
 * whole batch before reporting the cycle.
 *
 * <p>Each cycle:
 * <ol>
 *   <li>Runs TraefikController first, sequentially — it provisions the ingress overlay network that
 *       the Deployment reconciliation then attaches workloads to</li>
 *   <li>Runs DeploymentController, ServiceController, ConfigMapController, SecretController
 *       in parallel</li>
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

    private final TraefikController    traefikCtrl;
    private final DeploymentController deploymentCtrl;
    private final ServiceController    serviceCtrl;
    private final ConfigMapController  configMapCtrl;
    private final SecretController     secretCtrl;
    private final RiggerEventBus       eventBus;

    public ReconciliationLoop(TraefikController    traefikCtrl,
                               DeploymentController deploymentCtrl,
                               ServiceController    serviceCtrl,
                               ConfigMapController  configMapCtrl,
                               SecretController     secretCtrl,
                               RiggerEventBus       eventBus) {
        this.traefikCtrl    = traefikCtrl;
        this.deploymentCtrl = deploymentCtrl;
        this.serviceCtrl    = serviceCtrl;
        this.configMapCtrl  = configMapCtrl;
        this.secretCtrl     = secretCtrl;
        this.eventBus       = eventBus;
    }

    @Scheduled(fixedDelayString = "${rigger.operator.reconcile-interval-seconds:15}000")
    public void reconcile() {
        var start = Instant.now();
        log.debug("Reconciliation cycle starting...");

        int totalChanges = 0;
        int errors = 0;

        // Ingress infrastructure first, and deliberately NOT in the parallel batch below: it creates
        // the overlay network that DeploymentController then attaches workloads to. Run concurrently,
        // the very first cycle after enabling ingress would hash and write specs before the network
        // existed, producing labels with no attachment and one wasted update to fix it.
        totalChanges += runController("Traefik", traefikCtrl::reconcile);

        // One virtual thread per controller so each can block on Docker I/O concurrently.
        // runController swallows and logs each controller's own failures, so a single bad
        // controller degrades to "0 changes" rather than aborting the cycle — which is why a
        // plain invokeAll is enough here and no shared cancellation policy is needed.
        List<Callable<Integer>> tasks = List.of(
            () -> runController("Deployment", deploymentCtrl::reconcile),
            () -> runController("Service",    serviceCtrl::reconcile),
            () -> runController("ConfigMap",  configMapCtrl::reconcile),
            () -> runController("Secret",     secretCtrl::reconcile));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(tasks)) {
                totalChanges += future.get();
            }
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
