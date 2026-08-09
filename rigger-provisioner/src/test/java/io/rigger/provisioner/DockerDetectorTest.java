package io.rigger.provisioner;

import io.rigger.provisioner.docker.*;
import io.rigger.provisioner.ssh.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DockerDetectorTest {

    @Mock SshSession session;

    DockerDetector detector = new DockerDetector();

    @Test void dockerInstalled_returnsTrue() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(0, "Docker version 26.1.4\n26.1.4", "", "docker --version"));
        assertTrue(detector.isDockerInstalled(session));
    }

    @Test void dockerNotInstalled_returnsFalse() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(127, "", "docker: command not found", "docker --version"));
        assertFalse(detector.isDockerInstalled(session));
    }

    @Test void detectDistro_ubuntu() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(0, "ID=ubuntu\nVERSION_CODENAME=jammy", "", "cat /etc/os-release"));
        assertEquals(LinuxDistro.DEBIAN, detector.detectDistro(session));
    }

    @Test void detectDistro_rocky() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(0, "ID=rocky\nNAME=\"Rocky Linux\"", "", "cat /etc/os-release"));
        assertEquals(LinuxDistro.RHEL, detector.detectDistro(session));
    }

    @Test void detectDistro_unknown() {
        when(session.exec(anyString()))
            .thenReturn(new SshCommandResult(0, "ID=archlinux", "", "cat /etc/os-release"));
        assertEquals(LinuxDistro.UNKNOWN, detector.detectDistro(session));
    }
}
