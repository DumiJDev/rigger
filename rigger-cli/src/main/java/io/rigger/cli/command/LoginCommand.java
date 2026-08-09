package io.rigger.cli.command;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.client.RiggerApiClient;
import picocli.CommandLine.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * riggerctl login [-u username] [-i]
 *
 * Authenticates with the server and saves the JWT token to ~/.rigger/token.
 * All subsequent commands use this token automatically.
 *
 * Default credentials: username=admin, password=admin
 * (change with RIGGER_ADMIN_PASSWORD on the server)
 */
@Command(name = "login",
         description = "Authenticate and save token locally",
         footer = {
             "",
             "Examples:",
             "  riggerctl login                          # prompts for password",
             "  riggerctl login -u admin -p mypassword",
             "  riggerctl login -i                       # skip TLS verification",
         })
public class LoginCommand implements Callable<Integer> {

    @Option(names = {"-u", "--username"}, description = "Username", defaultValue = "admin")
    String username;

    @Option(names = {"-p", "--password"}, description = "Password (prompted if not given)",
            interactive = true, arity = "0..1", echo = false)
    char[] password;

    @Option(names = {"--insecure", "-i"},
            description = "Skip TLS certificate verification (self-signed certs)")
    boolean insecure;

    @Override
    public Integer call() throws Exception {
        CliConfig cfg;
        try {
            cfg = CliConfig.load();
        } catch (IOException e) {
            System.err.println("CLI not initialised.");
            System.err.println("Run: riggerctl init --server https://<host>:7433 --insecure");
            return 1;
        }

        // Resolve effective insecure flag: CLI flag OR config flag
        boolean effectiveInsecure = insecure || cfg.insecure();

        String pass = password != null ? new String(password) : promptPassword();

        // Create a client WITHOUT token for the login request itself
        var client = new RiggerApiClient(cfg.server(), effectiveInsecure, cfg.caCertPath(), null);

        Map<?,?> response;
        try {
            response = client.post("/api/v1/auth/login",
                Map.of("username", username, "password", pass));
        } catch (javax.net.ssl.SSLHandshakeException | javax.net.ssl.SSLPeerUnverifiedException e) {
            System.err.println();
            System.err.println("✗ TLS certificate error.");
            System.err.println("  The server is using a self-signed certificate.");
            System.err.println("  Re-run with --insecure to skip verification:");
            System.err.println();
            System.err.println("  riggerctl login -i -u " + username);
            System.err.println();
            System.err.println("  Or re-configure the CLI to always skip TLS:");
            System.err.println("  riggerctl init --server " + cfg.server() + " --insecure");
            return 1;
        } catch (RiggerApiClient.ApiException e) {
            if (e.isUnauthorized()) {
                System.err.println("✗ Invalid username or password.");
            } else {
                System.err.println("✗ Login failed: " + e.getMessage());
            }
            return 1;
        } catch (Exception e) {
            System.err.println("✗ Connection failed: " + e.getMessage());
            System.err.println("  Is the server running at " + cfg.server() + " ?");
            return 1;
        }

        String token = (String) response.get("token");
        if (token == null) {
            System.err.println("✗ Server returned no token");
            return 1;
        }

        // Save token
        var tokenPath = Path.of(System.getProperty("user.home"), ".rigger", "token");
        Files.createDirectories(tokenPath.getParent());
        Files.writeString(tokenPath, token);
        tokenPath.toFile().setReadable(false, false);
        tokenPath.toFile().setReadable(true, true);

        System.out.println();
        System.out.println("✓ Logged in as: " + response.get("username"));
        System.out.println("  Role:          " + response.get("role"));
        Object ns = response.get("namespace");
        System.out.println("  Namespace:     " + (ns != null && !ns.toString().equals("null") ? ns : "(all)"));
        System.out.println("  Token saved to ~/.rigger/token");
        System.out.println();
        System.out.println("You can now run:");
        System.out.println("  riggerctl get nodes" + (effectiveInsecure ? " -i" : ""));
        System.out.println("  riggerctl get deployments -n " + cfg.defaultNamespace()
            + (effectiveInsecure ? " -i" : ""));
        return 0;
    }

    private String promptPassword() {
        if (System.console() != null) {
            return new String(System.console().readPassword("Password: "));
        }
        System.out.print("Password: ");
        return new java.util.Scanner(System.in).nextLine();
    }
}
