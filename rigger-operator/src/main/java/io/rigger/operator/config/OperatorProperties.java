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
