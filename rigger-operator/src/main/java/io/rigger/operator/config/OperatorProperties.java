package io.rigger.operator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Rigger operator.
 *
 * <pre>
 * rigger:
 *   operator:
 *     reconcile-interval-seconds: 15
 *     hpa-interval-seconds: 30
 *     enabled: true
 * </pre>
 *
 * <p><strong>Nothing injects this bean, and that is on purpose — but read this before editing a
 * default here and expecting it to take effect.</strong> Spring resolves {@code fixedDelayString}
 * from the Environment, not from a bound object, so the intervals are consumed as SpEL directly on
 * the {@code @Scheduled} annotations ({@code ${rigger.operator.reconcile-interval-seconds:15}000} in
 * {@code ReconciliationLoop}, likewise in {@code HpaController}) and the fallbacks written there are
 * the ones that actually apply. The fields below exist so the keys carry types and show up in
 * configuration metadata; changing a default here changes nothing at runtime, so change both or
 * neither.
 *
 * <p>{@link #isEnabled()} is likewise never called: the flag is honoured structurally by
 * {@code OperatorAutoConfiguration.OperatorLoops}, which is {@code @ConditionalOnProperty} on it and
 * is the only thing that registers the three scheduled loop beans. It used to have no consumer at
 * all, which made {@code rigger.operator.enabled=false} a flag that stopped nothing.
 */
@Component
@ConfigurationProperties(prefix = "rigger.operator")
public class OperatorProperties {
    private int     reconcileIntervalSeconds = 15;
    private int     hpaIntervalSeconds       = 30;
    private boolean enabled                  = true;

    public int getReconcileIntervalSeconds() { return reconcileIntervalSeconds; }
    public void setReconcileIntervalSeconds(int s) { this.reconcileIntervalSeconds = s; }
    public int getHpaIntervalSeconds() { return hpaIntervalSeconds; }
    public void setHpaIntervalSeconds(int s) { this.hpaIntervalSeconds = s; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
