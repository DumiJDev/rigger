package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.util.Map;
import java.util.concurrent.Callable;

/** riggerctl scale deployment payments-api --replicas 5 -n prod */
@Command(name = "scale", description = "Scale a Deployment to the specified number of replicas")
public class ScaleCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Resource type (deployment)") String kind;
    @Parameters(index = "1", description = "Resource name")              String name;
    @Option(names = {"--replicas", "-r"}, required = true)               int replicas;
    @Option(names = {"-n", "--namespace"})                               String namespace;
    @Option(names = {"--insecure", "-i"})                                boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        cfg.client(insecure).post(
            "/api/v1/namespaces/" + namespace + "/deployments/" + name + "/scale",
            Map.of("replicas", replicas));
        System.out.println("✓ " + name + " scaled to " + replicas + " replicas");
        return 0;
    }
}
