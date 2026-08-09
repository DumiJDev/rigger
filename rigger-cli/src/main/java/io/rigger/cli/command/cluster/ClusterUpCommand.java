package io.rigger.cli.command.cluster;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** riggerctl cluster up --file rigger.cluster.yaml [-i] */
@Command(name = "up", description = "Provision and initialise cluster from rigger.cluster.yaml")
public class ClusterUpCommand implements Callable<Integer> {

    @Option(names = {"--file", "-f"}, required = true, description = "Path to rigger.cluster.yaml")
    Path file;

    @Option(names = {"--dev"}, description = "Single-node dev mode (no SSH, local Docker)")
    boolean dev;

    @Option(names = {"--insecure", "-i"}, description = "Skip TLS verification")
    boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg    = CliConfig.load();
        var client = cfg.client(insecure);
        System.out.println("Sending cluster manifest to " + cfg.server() + " …");
        var result = client.post("/api/v1/cluster/up",
            Map.of("manifest", Files.readString(file), "dev", dev));
        System.out.println("✓ " + result);
        return 0;
    }
}
