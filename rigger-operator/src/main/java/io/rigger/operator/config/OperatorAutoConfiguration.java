package io.rigger.operator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.rigger.operator.autoscaler.HpaController;
import io.rigger.operator.autoscaler.MetricsSource;
import io.rigger.operator.metrics.MetricsSampler;
import io.rigger.operator.reconcile.ReconciliationLoop;

/**
 * Auto-configuration for the Rigger operator.
 *
 * <p>{@code @EnableScheduling} lives here and is unconditional: it is what makes every
 * {@code @Scheduled} method in the application run, including {@code GitOpsAgent.poll} over in
 * {@code rigger-gitops}, which is deliberately always registered so GitOps can be switched on from
 * the console without a restart. Gating scheduling itself on {@code rigger.operator.enabled} would
 * therefore have silently disabled GitOps too, so the flag is applied to the three operator loops
 * only — see {@link OperatorLoopToggle}.
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = "io.rigger.operator")
public class OperatorAutoConfiguration {

    /** Registers the stub MetricsSource if no other implementation is provided. */
    @Bean
    @ConditionalOnMissingBean(MetricsSource.class)
    public MetricsSource stubMetricsSource() {
        return MetricsSource.STUB;
    }

    /**
     * Makes {@code rigger.operator.enabled=false} actually stop the three background loops
     * (reconcile, HPA, metrics sampling), leaving the REST API, the console, the live metrics
     * endpoints and GitOps fully working. Before this the property had <em>zero</em> consumers:
     * {@code OperatorProperties.isEnabled()} was never called and setting it to false stopped
     * nothing whatsoever.
     *
     * <p><strong>Why a BeanFactoryPostProcessor and not {@code @ConditionalOnProperty}.</strong>
     * The obvious form — a nested {@code @ConditionalOnProperty} configuration that imports the
     * three loop classes, with this class's component scan excluding them — was implemented first
     * and verified not to work: {@code RiggerApplication} carries
     * {@code @ComponentScan(basePackages = "io.rigger")}, so the application's own scan sweeps up
     * every {@code @Component} in every module and re-registers the loops no matter what this
     * auto-configuration decides. (That blanket scan is worth knowing about generally: it means a
     * conditional expressed in any module's auto-configuration cannot suppress a {@code @Component}
     * in that module.) Removing the definitions after scanning is the one place a single module can
     * express this and have it hold. The tidier long-term fix is a {@code @ConditionalOnProperty} on
     * each of the three classes, which would make this post-processor unnecessary.
     *
     * <p>Registered {@code static} and returning a {@code BeanFactoryPostProcessor}, per Spring's
     * requirement that BFPP factory methods be static so the enclosing configuration need not be
     * instantiated early.
     */
    @Bean
    static BeanFactoryPostProcessor operatorLoopToggle(Environment environment) {
        return new OperatorLoopToggle(environment);
    }

    /** Removes the loop bean definitions when the operator is disabled. */
    static final class OperatorLoopToggle implements BeanFactoryPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(OperatorLoopToggle.class);

        /** Defaults to enabled: an operator that silently does nothing unless asked is the worse trap. */
        private static final boolean DEFAULT_ENABLED = true;

        private static final Class<?>[] LOOPS = { ReconciliationLoop.class, HpaController.class, MetricsSampler.class };

        private final Environment environment;

        OperatorLoopToggle(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (environment.getProperty("rigger.operator.enabled", Boolean.class, DEFAULT_ENABLED)) {
                return;
            }
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                log.warn("rigger.operator.enabled=false could not be applied: bean factory is not a registry");
                return;
            }
            for (var loop : LOOPS) {
                for (var name : beanFactory.getBeanNamesForType(loop, false, false)) {
                    registry.removeBeanDefinition(name);
                }
            }
            log.warn("rigger.operator.enabled=false — reconciliation, HPA and metrics sampling are DISABLED. "
                + "Nothing will be written to Swarm and no metric history will be recorded. "
                + "The REST API, the console and GitOps are unaffected.");
        }
    }
}
