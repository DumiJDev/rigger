package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** riggerctl apply -f manifest.yaml [-n namespace] [-i] */
@Command(name = "apply", description = "Apply one or more manifests to the cluster")
public class ApplyCommand implements Callable<Integer> {

    @Option(names = {"-f", "--file"}, required = true,
            description = "Manifest file, directory, or docker-compose.yml")
    Path file;

    @Option(names = {"-n", "--namespace"}, description = "Target namespace (overrides config default)")
    String namespace;

    @Option(names = {"--dry-run"}, description = "Validate without applying")
    boolean dryRun;

    @Option(names = {"--insecure", "-i"}, description = "Skip TLS verification")
    boolean insecure;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        var client = cfg.client(insecure);

        List<Path> files = Files.isDirectory(file)
            ? Files.list(file)
                .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .sorted().toList()
            : List.of(file);

        int total = 0;
        for (var f : files) {
            var result = client.post("/api/v1/namespaces/" + namespace + "/apply",
                Map.of("manifest", Files.readString(f), "dryRun", dryRun));
            var applied = ((Map<?,?>) result).get("applied");
            int n = applied instanceof Number num ? num.intValue() : 0;
            System.out.printf("%-50s  %d resource(s) %s%n",
                f.getFileName(), n, dryRun ? "(dry-run)" : "applied");
            total += n;
        }
        System.out.println("Total: " + total + " resource(s)");
        return 0;
    }
}
