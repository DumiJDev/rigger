package io.rigger.cli.command.user;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

// ── create ────────────────────────────────────────────────────────────
@Command(name = "create", description = "Create a new user")
public class CreateUser implements Callable<Integer> {
  @Parameters(index = "0", description = "Username")
  String username;
  @Option(names = {"--role", "-r"}, required = true,
      description = "Role: cluster-admin | deployer | viewer | gitops-agent")
  String role;
  @Option(names = {"--namespace", "-n"}, description = "Namespace scope (required for deployer/viewer)")
  String namespace;
  @Option(names = {"--password", "-p"}, required = true,
      description = "Initial password", interactive = true, arity = "0..1", echo = false)
  char[] password;
  @Option(names = {"--insecure", "-i"})
  boolean insecure;

  @Override
  public Integer call() throws Exception {
    var cfg = CliConfig.load();
    var pass = password != null ? new String(password) : "changeme";
    cfg.client(insecure).post("/api/v1/users", Map.of(
        "username", username, "password", pass,
        "role", role.toUpperCase().replace("-", "_"),
        "namespace", namespace != null ? namespace : ""));
    System.out.println("✓ User created: " + username + " (" + role + ")");
    return 0;
  }
}
