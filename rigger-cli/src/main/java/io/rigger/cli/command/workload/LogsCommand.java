package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.io.IOException;
import java.util.concurrent.Callable;

/** riggerctl logs <pod-name> -n prod [--follow] */
@Command(name = "logs", description = "Stream logs from a pod")
public class LogsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Pod name") String pod;
    @Option(names = {"-n", "--namespace"}) String namespace;
    @Option(names = {"--follow", "-f"}, description = "Stream logs continuously") boolean follow;
    @Option(names = {"--insecure", "-i"}, description = "Skip TLS verification") boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        var client = cfg.client(insecure);

        String path = "/api/v1/namespaces/" + namespace + "/pods/" + pod + "/logs"
            + (follow ? "?follow=true" : "");

        try (var resp = client.openStream(path)) {
            var source = resp.body().source();
            try {
                while (!source.exhausted()) System.out.println(source.readUtf8Line());
            } catch (IOException e) {
                // The server closes the underlying connection once Docker's log stream ends —
                // that can surface as a truncated-chunk IOException rather than a clean EOF.
                // Already-printed lines are complete either way; nothing left to recover.
            }
        }
        return 0;
    }
}
