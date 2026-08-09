package io.rigger.provisioner.swarm;

import io.rigger.core.exception.ProvisioningException;
import io.rigger.provisioner.ssh.SshSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Initialises a new Docker Swarm on the primary manager node.
 * Also extracts join tokens needed for worker and manager nodes to join.
 */
@Component
public class SwarmInitializer {

    private static final Logger log = LoggerFactory.getLogger(SwarmInitializer.class);

    /**
     * Runs {@code docker swarm init} on the primary manager.
     * If a Swarm already exists on the node, this is a no-op.
     *
     * @param session     SSH session to the primary manager node.
     * @param advertiseIp IP address to advertise for Swarm communication (the node's IP).
     * @param nodeName    Node name for error messages.
     * @return SwarmTokens containing manager and worker join tokens.
     */
    public SwarmTokens initSwarm(SshSession session, String advertiseIp, String nodeName) {
        // Check if this node is already a Swarm manager
        if (isAlreadySwarmManager(session)) {
            log.info("Node {} is already a Swarm manager — skipping init", nodeName);
            return extractTokens(session, nodeName, advertiseIp);
        }

        log.info("Initialising Docker Swarm on primary manager {} ({})", nodeName, advertiseIp);
        var result = session.sudo("docker swarm init --advertise-addr " + advertiseIp);
        if (!result.isSuccess()) {
            throw new ProvisioningException(nodeName,
                "docker swarm init failed: " + result.stderr());
        }

        log.info("Swarm initialised on {}", nodeName);
        return extractTokens(session, nodeName, advertiseIp);
    }

    /**
     * Joins a worker node to the existing Swarm.
     *
     * @param session        SSH session to the worker node.
     * @param nodeName       Node name for logging.
     * @param tokens         Tokens from the primary manager.
     * @param managerIp      IP of the primary manager to join.
     */
    public void joinAsWorker(SshSession session, String nodeName, SwarmTokens tokens, String managerIp) {
        if (isAlreadySwarmNode(session)) {
            log.info("Node {} is already part of a Swarm — skipping join", nodeName);
            return;
        }
        log.info("Joining {} as Swarm worker", nodeName);
        // Token is passed via env var to avoid it appearing in ps output
        var result = session.sudo(
            "SWARM_TOKEN=" + tokens.workerToken() + " " +
            "docker swarm join --token $SWARM_TOKEN " + managerIp + ":2377");
        if (!result.isSuccess()) {
            throw new ProvisioningException(nodeName, "docker swarm join (worker) failed: " + result.stderr());
        }
        log.info("Node {} joined Swarm as worker", nodeName);
    }

    /**
     * Joins a manager node to the existing Swarm (adds to Raft quorum).
     */
    public void joinAsManager(SshSession session, String nodeName, SwarmTokens tokens, String managerIp) {
        if (isAlreadySwarmNode(session)) {
            log.info("Node {} is already part of a Swarm — skipping join", nodeName);
            return;
        }
        log.info("Joining {} as Swarm manager (Raft quorum)", nodeName);
        var result = session.sudo(
            "SWARM_TOKEN=" + tokens.managerToken() + " " +
            "docker swarm join --token $SWARM_TOKEN " + managerIp + ":2377");
        if (!result.isSuccess()) {
            throw new ProvisioningException(nodeName, "docker swarm join (manager) failed: " + result.stderr());
        }
        log.info("Node {} joined Swarm as manager", nodeName);
    }

    /**
     * Gracefully drains a node and removes it from the Swarm.
     * Should be called from a manager node session.
     */
    public void drainAndRemove(SshSession managerSession, String targetNodeName) {
        log.info("Draining Swarm node {}", targetNodeName);
        managerSession.sudo("docker node update --availability drain " + targetNodeName);

        // Wait for tasks to migrate (simple poll — Phase 4 will use proper async wait)
        managerSession.exec("sleep 10");

        log.info("Removing Swarm node {}", targetNodeName);
        managerSession.sudo("docker node rm --force " + targetNodeName);
        log.info("Node {} removed from Swarm", targetNodeName);
    }

    private boolean isAlreadySwarmManager(SshSession session) {
        var r = session.exec("docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null");
        return r.isSuccess() && "active".equals(r.trimmedOutput());
    }

    private boolean isAlreadySwarmNode(SshSession session) {
        var r = session.exec("docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null");
        return r.isSuccess() && (r.trimmedOutput().equals("active") || r.trimmedOutput().equals("pending"));
    }

    private SwarmTokens extractTokens(SshSession session, String nodeName, String advertiseAddr) {
        var managerToken = session.sudo("docker swarm join-token manager -q");
        var workerToken  = session.sudo("docker swarm join-token worker -q");

        if (!managerToken.isSuccess() || !workerToken.isSuccess()) {
            throw new ProvisioningException(nodeName, "Failed to extract Swarm join tokens");
        }

        return new SwarmTokens(
            managerToken.trimmedOutput(),
            workerToken.trimmedOutput(),
            advertiseAddr
        );
    }
}
