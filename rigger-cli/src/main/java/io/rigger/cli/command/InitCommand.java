package io.rigger.cli.command;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.client.RiggerApiClient;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.Callable;

/**
 * riggerctl init --server https://host:7433 [--insecure]
 *
 * Configures the CLI to connect to a Rigger server.
 * Use --insecure if the server has a self-signed certificate.
 */
@Command(name = "init",
         description = "Configure CLI to connect to a Rigger server",
         footer = {
             "",
             "Examples:",
             "  riggerctl init --server https://10.0.0.10:7433",
             "  riggerctl init --server https://10.0.0.10:7433 --insecure",
             "  riggerctl init --server https://localhost:7433   --insecure",
         })
public class InitCommand implements Callable<Integer> {

    @Option(names = {"--server", "-s"}, required = true,
            description = "Rigger server URL (e.g. https://10.0.0.10:7433)")
    String server;

    @Option(names = {"--namespace", "-n"}, defaultValue = "default",
            description = "Default namespace for commands")
    String namespace;

    @Option(names = {"--insecure", "-i"},
            description = "Skip TLS certificate verification (for self-signed certs)")
    boolean insecure;

    @Option(names = {"--ca-cert"},
            description = "Path to CA certificate file for TLS verification")
    String caCert;

    @Override
    public Integer call() throws Exception {
        var identityDir = Path.of(System.getProperty("user.home"), ".rigger", "identity");
        Files.createDirectories(identityDir);

        // Generate EC keypair
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var kp  = kpg.generateKeyPair();

        var privPath = identityDir.resolve("private.key");
        Files.writeString(privPath, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        privPath.toFile().setReadable(false, false);
        privPath.toFile().setReadable(true, true);

        var pubPath = identityDir.resolve("public.key");
        Files.writeString(pubPath, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));

        String effectiveCaCert = caCert != null ? caCert : identityDir.resolve("ca.crt").toString();

        var config = new CliConfig(server, identityDir.resolve("cert.pem").toString(),
            privPath.toString(), effectiveCaCert, namespace, insecure);
        config.save();

        System.out.println();
        System.out.println("✓ Config saved to ~/.rigger/config");
        System.out.println("  Server:    " + server);
        System.out.println("  Namespace: " + namespace);
        System.out.println("  Insecure:  " + insecure);
        System.out.println();

        // Test server connectivity
        System.out.print("Testing connection to " + server + " ... ");
        boolean connected = testConnection(config);

        if (!connected && !insecure) {
            System.out.println();
            System.out.println("  TLS certificate error detected.");
            System.out.println("  If the server uses a self-signed certificate, re-run with --insecure:");
            System.out.println();
            System.out.println("  riggerctl init --server " + server + " --insecure");
            System.out.println();
            return 1;
        }

        if (connected) {
            System.out.println();
            System.out.println("Next step — login:");
            System.out.println("  riggerctl login -u admin" + (insecure ? " -i" : ""));
        }
        return 0;
    }

    private boolean testConnection(CliConfig config) {
        try {
            // Test health endpoint (public, no token needed)
            new RiggerApiClient(config.server(), config.insecure() || insecure,
                config.caCertPath(), null)
                .get("/actuator/health", Object.class);
            System.out.println("✓ reachable");
            return true;
        } catch (javax.net.ssl.SSLHandshakeException | javax.net.ssl.SSLPeerUnverifiedException e) {
            System.out.println("✗ TLS error");
            return false;
        } catch (Exception e) {
            // Other errors (connection refused, timeout) — might still work for login
            System.out.println("⚠ " + e.getMessage());
            return true; // don't block on non-TLS errors
        }
    }
}
