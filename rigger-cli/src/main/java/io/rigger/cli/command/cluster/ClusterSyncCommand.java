package io.rigger.cli.command.cluster;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** riggerctl cluster sync --file rigger.cluster.yaml */
@Command(name = "sync", description = "Reconcile cluster nodes with rigger.cluster.yaml")
public class ClusterSyncCommand implements Callable<Integer> {

    @Option(names = {"--file", "-f"}, required = true) Path file;
    @Option(names = {"--insecure", "-i"}) boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg    = CliConfig.load();
        var result = cfg.client(insecure).post("/api/v1/cluster/sync",
            Map.of("manifest", Files.readString(file)));
        System.out.println("✓ " + result);
        return 0;
    }
}
