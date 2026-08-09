package io.rigger.cli.command.user;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

// ── revoke ────────────────────────────────────────────────────────────
  @Command(name = "revoke", description = "Revoke a user's access")
  public class RevokeUser implements Callable<Integer> {
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
