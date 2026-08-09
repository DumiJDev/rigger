package io.rigger.cli.command.cluster;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.output.TablePrinter;
import picocli.CommandLine.*;
import java.util.*;
import java.util.concurrent.Callable;

/** riggerctl cluster status */
@Command(name = "status", description = "Show cluster node status")
public class ClusterStatusCommand implements Callable<Integer> {

    @Option(names = {"--insecure", "-i"}) boolean insecure;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() throws Exception {
        var cfg   = CliConfig.load();
        var nodes = (List<?>) cfg.client(insecure).get("/api/v1/cluster/nodes", List.class);
        TablePrinter.print(
            List.of("NAME", "IP", "ROLE", "STATUS", "PRIMARY"),
            nodes.stream().map(n -> {
                var m = (Map<String,Object>) n;
                return List.of(s(m,"name"), s(m,"ip"), s(m,"role"),
                               s(m,"status"), String.valueOf(m.getOrDefault("primary",false)));
            }).toList()
        );
        return 0;
    }
    private String s(Map<String,Object> m, String k) { return m.getOrDefault(k,"").toString(); }
}
