package io.rigger.core.exception;

/** Thrown when a node provisioning step fails (SSH, Docker install, Swarm join). */
public class ProvisioningException extends RiggerException {
    private final String nodeName;

    public ProvisioningException(String nodeName, String message) {
        super("Provisioning failed for node '" + nodeName + "': " + message);
        this.nodeName = nodeName;
    }

    public ProvisioningException(String nodeName, String message, Throwable cause) {
        super("Provisioning failed for node '" + nodeName + "': " + message, cause);
        this.nodeName = nodeName;
    }

    public String nodeName() { return nodeName; }
}
