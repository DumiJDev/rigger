package io.rigger.core.domain.security;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class RiggerContextTest {
    RiggerIdentity identity(RiggerRole role, String ns) {
        return new RiggerIdentity("id1","alice",role,ns,"s1",Instant.now(),null,Map.of());
    }
    @Test void validContext_builds() {
        var c = new RiggerContext(identity(RiggerRole.DEPLOYER,"prod"),"prod","1.2.3.4",Instant.now());
        assertEquals("alice", c.identityName());
    }
    @Test void nullIdentity_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new RiggerContext(null,"prod","1.2.3.4",Instant.now()));
    }
    @Test void blankNamespace_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new RiggerContext(identity(RiggerRole.DEPLOYER,"prod")," ","1.2.3.4",Instant.now()));
    }
    @Test void deployer_scopedToOwnNs() {
        var id = identity(RiggerRole.DEPLOYER,"prod");
        assertTrue(id.isScopedTo("prod"));
        assertFalse(id.isScopedTo("staging"));
    }
    @Test void clusterAdmin_scopedToAnyNs() {
        var admin = identity(RiggerRole.CLUSTER_ADMIN,null);
        assertTrue(admin.isScopedTo("prod"));
        assertTrue(admin.isScopedTo("staging"));
    }
}