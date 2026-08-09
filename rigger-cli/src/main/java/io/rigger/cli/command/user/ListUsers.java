package io.rigger.cli.command.user;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.output.TablePrinter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

// ── list ─────────────────────────────────────────────────────────────
@Command(name = "list", description = "List all users")
public class ListUsers implements Callable<Integer> {
  @Option(names = {"--insecure", "-i"})
  boolean insecure;

  @Override
  @SuppressWarnings("unchecked")
  public Integer call() throws Exception {
    var cfg = CliConfig.load();
    var users = (List<?>) cfg.client(insecure).get("/api/v1/users", List.class);
    TablePrinter.print(
        List.of("USERNAME", "ROLE", "NAMESPACE", "ACTIVE"),
        users.stream().map(u -> {
          var m = (Map<String, Object>) u;
          return List.of(s(m, "username"), s(m, "role"),
              s(m, "namespace"), String.valueOf(m.getOrDefault("active", true)));
        }).toList()
    );
    return 0;
  }

  private String s(Map<String, Object> m, String k) {
    var val = m.getOrDefault(k, "-");

    if (val == null) {
      return "-";
    }

    return val.toString();
  }
}
