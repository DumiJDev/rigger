package io.rigger.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import java.util.ArrayList;
import java.util.List;

/**
 * Rigger Server entry point.
 *
 * <p>Start-up behaviour:
 * <ul>
 *   <li>Detects Windows OS and activates the "windows" Spring profile automatically
 *       (switches Docker socket to the named pipe).</li>
 *   <li>Validates required environment variables. If RIGGER_MASTER_KEY is absent,
 *       generates a random one for dev mode and logs a clear warning.</li>
 *   <li>Supports {@code RIGGER_ATTACH_EXISTING_SWARM=true} to skip provisioning
 *       and connect to an already-running Docker Swarm.</li>
 * </ul>
 */
@SpringBootApplication
@ComponentScan(basePackages = "io.rigger")
public class RiggerApplication {

    private static final Logger log = LoggerFactory.getLogger(RiggerApplication.class);

    public static void main(String[] args) {
        var app = new SpringApplication(RiggerApplication.class);

        // ── Windows: activate the windows profile automatically ──────────
        List<String> profiles = new ArrayList<>();
        if (isWindows()) {
            profiles.add("windows");
            log.info("Windows detected — activating 'windows' profile (Docker named pipe)");
        }
        if (!profiles.isEmpty()) {
            app.setAdditionalProfiles(profiles.toArray(String[]::new));
        }

        // ── Dev mode: generate a random master key if not provided ───────
        if (isBlank(System.getenv("RIGGER_MASTER_KEY"))) {
            String generated = generateDevKey();
            System.setProperty("RIGGER_MASTER_KEY", generated);
            log.warn("╔══════════════════════════════════════════════════════╗");
            log.warn("║  RIGGER_MASTER_KEY not set — using generated dev key ║");
            log.warn("║  Secrets will be lost on server restart!             ║");
            log.warn("║  For production, set: export RIGGER_MASTER_KEY=...   ║");
            log.warn("║  Generate: openssl rand -base64 32                   ║");
            log.warn("╚══════════════════════════════════════════════════════╝");
        }

        app.run(args);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String generateDevKey() {
        var random = new java.security.SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }
}
