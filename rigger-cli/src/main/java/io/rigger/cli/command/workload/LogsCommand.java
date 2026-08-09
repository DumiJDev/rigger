package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import okhttp3.*;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/** riggerctl logs <pod-name> -n prod [--follow] */
@Command(name = "logs", description = "Stream logs from a pod")
public class LogsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Pod name") String pod;
    @Option(names = {"-n", "--namespace"}) String namespace;
    @Option(names = {"--follow", "-f"}, description = "Stream logs continuously") boolean follow;

    @Override
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();

        String url = cfg.server() + "/api/v1/namespaces/" + namespace + "/pods/" + pod + "/logs"
            + (follow ? "?follow=true" : "");

        var req  = new Request.Builder().url(url).get().build();
        var http = new OkHttpClient();
        try (var resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) { System.err.println("Error: " + resp.code()); return 1; }
            var source = resp.body().source();
            while (!source.exhausted()) System.out.println(source.readUtf8Line());
        }
        return 0;
    }
}
