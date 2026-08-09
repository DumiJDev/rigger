package io.rigger.provisioner.ssh;

import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * SshSession backed by Apache MINA SSHD.
 * Each exec() call opens a new channel — this is the correct MINA pattern
 * (one channel per command, one session per node connection).
 *
 * <p>Command timeout defaults to 5 minutes, suitable for Docker installation.
 */
public class MinaSshSession implements SshSession {

    private static final Logger log = LoggerFactory.getLogger(MinaSshSession.class);
    private static final long COMMAND_TIMEOUT_SEC = 300; // 5 minutes

    private final ClientSession session;
    private final String host;

    public MinaSshSession(ClientSession session, String host) {
        this.session = session;
        this.host = host;
    }

    @Override
    public SshCommandResult exec(String command) {
        log.debug("SSH exec on {}: {}", host, command);
        try {
            var outBuf = new ByteArrayOutputStream();
            var errBuf = new ByteArrayOutputStream();

            try (ClientChannel channel = session.createExecChannel(command)) {
                channel.setOut(outBuf);
                channel.setErr(errBuf);
                channel.open().verify(10, TimeUnit.SECONDS);

                channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED),
                    TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SEC)
                );

                int exitCode = channel.getExitStatus() != null ? channel.getExitStatus() : -1;
                String stdout = outBuf.toString(StandardCharsets.UTF_8);
                String stderr = errBuf.toString(StandardCharsets.UTF_8);

                var result = new SshCommandResult(exitCode, stdout, stderr, command);
                if (!result.isSuccess()) {
                    log.warn("SSH command failed on {}: exit={} stderr={}", host, exitCode, stderr.trim());
                }
                return result;
            }
        } catch (IOException e) {
            throw new io.rigger.core.exception.ProvisioningException(host,
                "SSH channel error for command \"" + command + "\"", e);
        }
    }

    @Override
    public String remoteHost() { return host; }

    @Override
    public void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close(false);
        }
    }
}
