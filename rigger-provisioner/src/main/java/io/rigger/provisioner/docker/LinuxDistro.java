package io.rigger.provisioner.docker;

/**
 * Linux distribution families detected on remote nodes.
 * Used to select the correct Docker installation command.
 */
public enum LinuxDistro {
    /** Debian, Ubuntu, Raspberry Pi OS */
    DEBIAN,
    /** RHEL, CentOS, Rocky Linux, AlmaLinux, Fedora */
    RHEL,
    /** Fallback — uses Docker's convenience install script */
    UNKNOWN
}
