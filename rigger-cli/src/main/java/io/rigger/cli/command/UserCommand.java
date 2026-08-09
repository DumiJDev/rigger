package io.rigger.cli.command;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.output.TablePrinter;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * riggerctl user <create|list|revoke>
 *
 * Requires CLUSTER_ADMIN role.
 */
@Command(name = "user", description = "Manage Rigger users (requires cluster-admin role)",
         subcommands = {UserCommand.CreateUser.class, UserCommand.ListUsers.class, UserCommand.RevokeUser.class})
public class UserCommand implements Callable<Integer> {
    @Override public Integer call() { CommandLine.usage(this, System.out); return 0; }

    // ── create ────────────────────────────────────────────────────────────
    @Command(name = "create", description = "Create a new user")
    static class CreateUser implements Callable<Integer> {
        @Parameters(index = "0", description = "Username") String username;
        @Option(names = {"--role", "-r"}, required = true,
            description = "Role: cluster-admin | deployer | viewer | gitops-agent") String role;
        @Option(names = {"--namespace", "-n"}, description = "Namespace scope (required for deployer/viewer)") String namespace;
        @Option(names = {"--password", "-p"}, required = true,
            description = "Initial password", interactive = true, arity = "0..1", echo = false) char[] password;
        @Option(names = {"--insecure", "-i"}) boolean insecure;

        @Override
        public Integer call() throws Exception {
            var cfg = CliConfig.load();
            var pass = password != null ? new String(password) : "changeme";
            cfg.client(insecure).post("/api/v1/users", Map.of(
                "username", username, "password", pass,
                "role", role.toUpperCase().replace("-","_"),
                "namespace", namespace != null ? namespace : ""));
            System.out.println("✓ User created: " + username + " (" + role + ")");
            return 0;
        }
    }

    // ── list ─────────────────────────────────────────────────────────────
    @Command(name = "list", description = "List all users")
    static class ListUsers implements Callable<Integer> {
        @Option(names = {"--insecure", "-i"}) boolean insecure;

        @Override
        @SuppressWarnings("unchecked")
        public Integer call() throws Exception {
            var cfg   = CliConfig.load();
            var users = (List<?>) cfg.client(insecure).get("/api/v1/users", List.class);
            TablePrinter.print(
                List.of("USERNAME", "ROLE", "NAMESPACE", "ACTIVE"),
                users.stream().map(u -> {
                    var m = (Map<String,Object>) u;
                    return List.of(s(m,"username"), s(m,"role"),
                        s(m,"namespace"), String.valueOf(m.getOrDefault("active",true)));
                }).toList()
            );
            return 0;
        }
        private String s(Map<String,Object> m, String k) { return m.getOrDefault(k,"-").toString(); }
    }

    // ── revoke ────────────────────────────────────────────────────────────
    @Command(name = "revoke", description = "Revoke a user's access")
    static class RevokeUser implements Callable<Integer> {
        @Parameters(index = "0", description = "Username to revoke") String username;
        @Option(names = {"--insecure", "-i"}) boolean insecure;

        @Override
        public Integer call() throws Exception {
            var cfg = CliConfig.load();
            cfg.client(insecure).delete("/api/v1/users/" + username);
            System.out.println("✓ Access revoked: " + username);
            return 0;
        }
    }
}
