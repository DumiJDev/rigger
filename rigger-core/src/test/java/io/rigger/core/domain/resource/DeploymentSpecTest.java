package io.rigger.core.domain.resource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeploymentSpecTest {
    @Test void validSpec_buildsWithDefaults() {
        var s = new DeploymentSpec(3,null,"app:1.0",null,null,null,null,null,null);
        assertEquals(RollingUpdateStrategy.DEFAULT, s.strategy());
    }
    @Test void negativeReplicas_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new DeploymentSpec(-1,null,"app:1.0",null,null,null,null,null,null));
    }
    @Test void blankImage_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new DeploymentSpec(1,null," ",null,null,null,null,null,null));
    }
    @Test void hpaMinGreaterThanMax_throws() {
        assertThrows(IllegalArgumentException.class, () -> new HpaSpec(10,2,70,80,180));
    }
    @Test void envVar_valuePlusRef_throws() {
        var ref = new EnvVarSource(new KeyRef("cfg","key"), null);
        assertThrows(IllegalArgumentException.class, () -> new EnvVar("VAR","value",ref));
    }
}