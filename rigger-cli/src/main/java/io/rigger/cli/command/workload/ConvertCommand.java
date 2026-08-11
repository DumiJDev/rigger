package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code riggerctl convert -f docker-compose.yml} — prints the {@code rigger.io/v1} YAML that
 * applying this Compose file would produce, and reports on stderr everything Rigger cannot express.
 *
 * <p>Compose input has been applied since the beginning, but there was no way to see what it turned
 * into: no endpoint, no command, no preview. Hence the split streams — the manifest goes to stdout so
 * {@code riggerctl convert -f docker-compose.yml > rigger.yaml} produces a file
 * {@code riggerctl apply -f rigger.yaml} accepts, and the report goes to stderr so that redirect
 * stays clean.
 *
 * <p>Exit code is 1 when at least one issue is an ERROR — the same condition that makes
 * {@code apply} refuse the Compose file. The YAML is still printed: it is the starting point for
 * fixing the file, and a caller that ignores exit codes gets the manifest either way.
 */
@Command(name = "convert",
    description = "Convert a docker-compose file to rigger.io/v1 YAML (stdout) + a loss report (stderr)")
public class ConvertCommand implements Callable<Integer> {

    @Option(names = {"-f", "--file"}, required = true, description = "docker-compose file to convert")
    Path file;

    @Option(names = {"-n", "--namespace"}, description = "Target namespace (overrides config default)")
    String namespace;

    @Option(names = {"--quiet", "-q"}, description = "Suppress the report on stderr")
    boolean quiet;

    @Option(names = {"--insecure", "-i"}, description = "Skip TLS verification")
    boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        // The same authenticated client every other command uses — conversion is a server-side
        // operation (it is the server's converter that apply will run), so it goes through the API
        // rather than a second copy of the logic in the CLI.
        var client = cfg.client(insecure);

        var result = client.post("/api/v1/namespaces/" + namespace + "/convert",
            Map.of("content", Files.readString(file)));

        Object yaml = result.get("yaml");
        if (yaml != null) System.out.print(yaml);

        boolean blocked = Boolean.TRUE.equals(result.get("blocked"));
        var issues = result.get("issues") instanceof List<?> list ? list : List.of();

        if (!quiet) {
            if (issues.isEmpty()) {
                System.err.println("# converted with nothing lost");
            } else {
                System.err.println("# " + issues.size() + " issue(s) converting " + file.getFileName() + ":");
                for (var issue : issues) {
                    if (issue instanceof Map<?, ?> m) {
                        System.err.printf("#   [%s] %s — %s%n",
                            m.get("severity"), m.get("path"), m.get("message"));
                    }
                }
            }
            if (blocked) {
                System.err.println("# ERROR-level issues above: `riggerctl apply -f "
                    + file.getFileName() + "` will refuse this file. Apply the converted YAML "
                    + "instead, after fixing what matters.");
            }
        }
        return blocked ? 1 : 0;
    }
}
