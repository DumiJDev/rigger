package io.rigger.cli.command;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

/** riggerctl whoami — show current CLI config and login state */
@Command(name = "whoami", description = "Show current CLI identity and connection state")
public class WhoAmICommand implements Callable<Integer> {

    @Option(names = {"--insecure", "-i"}) boolean insecure;

    @Override
    public Integer call() throws Exception {
        CliConfig cfg;
        try {
            cfg = CliConfig.load();
        } catch (Exception e) {
            System.out.println("Not initialised. Run: riggerctl init --server https://<host>:7433");
            return 1;
        }

        System.out.println("Server:    " + cfg.server());
        System.out.println("Namespace: " + cfg.defaultNamespace());
        System.out.println("Insecure:  " + cfg.insecure());

        var tokenPath = Path.of(System.getProperty("user.home"), ".rigger", "token");
        if (Files.exists(tokenPath)) {
            System.out.println("Token:     present (run `riggerctl login` to refresh)");
            // Try to get current identity from server
            try {
                var me = cfg.client(insecure).get("/api/v1/auth/me",
                    java.util.Map.class);
                System.out.println("Logged in: " + me.get("username") +
                    " (" + me.get("role") + ")");
            } catch (Exception e) {
                System.out.println("Logged in: unknown (token may be expired — run `riggerctl login`)");
            }
        } else {
            System.out.println("Token:     not found — run `riggerctl login -u admin`");
        }
        return 0;
    }
}
