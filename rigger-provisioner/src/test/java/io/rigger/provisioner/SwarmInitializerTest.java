package io.rigger.provisioner;

import io.rigger.core.exception.ProvisioningException;
import io.rigger.provisioner.ssh.*;
import io.rigger.provisioner.swarm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmInitializerTest {

    @Mock SshSession session;

    SwarmInitializer initializer = new SwarmInitializer();

    @Test void initSwarm_notYetSwarm_initsAndReturnsTokens() {
        // not already a swarm manager
        when(session.exec("docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null"))
            .thenReturn(new SshCommandResult(0, "inactive", "", ""));
        // swarm init succeeds
        when(session.sudo(contains("swarm init")))
            .thenReturn(new SshCommandResult(0, "Swarm initialized", "", ""));
        // token extraction
        when(session.sudo(contains("join-token manager")))
            .thenReturn(new SshCommandResult(0, "SWMTKN-1-manager-token", "", ""));
        when(session.sudo(contains("join-token worker")))
            .thenReturn(new SshCommandResult(0, "SWMTKN-1-worker-token", "", ""));

        var tokens = initializer.initSwarm(session, "10.0.0.10", "manager-01");
        assertEquals("SWMTKN-1-manager-token", tokens.managerToken());
        assertEquals("SWMTKN-1-worker-token", tokens.workerToken());
    }

    @Test void initSwarm_alreadyManager_skipsInit() {
        when(session.exec("docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null"))
            .thenReturn(new SshCommandResult(0, "active", "", ""));
        when(session.sudo(contains("join-token manager")))
            .thenReturn(new SshCommandResult(0, "SWMTKN-1-manager-token", "", ""));
        when(session.sudo(contains("join-token worker")))
            .thenReturn(new SshCommandResult(0, "SWMTKN-1-worker-token", "", ""));

        var tokens = initializer.initSwarm(session, "10.0.0.10", "manager-01");
        assertNotNull(tokens);
        // swarm init should NOT have been called
        verify(session, never()).sudo(contains("swarm init"));
    }

    @Test void initSwarm_fails_throwsProvisioningException() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(0, "inactive", "", ""));
        when(session.sudo(contains("swarm init")))
            .thenReturn(new SshCommandResult(1, "", "Error response from daemon", ""));

        assertThrows(ProvisioningException.class,
            () -> initializer.initSwarm(session, "10.0.0.10", "manager-01"));
    }
}
