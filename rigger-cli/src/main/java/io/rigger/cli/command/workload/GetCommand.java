package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import io.rigger.cli.output.TablePrinter;
import picocli.CommandLine.*;
import java.util.*;
import java.util.concurrent.Callable;

/** riggerctl get deployments|services|pods|nodes|configmaps|secrets */
@Command(name = "get", description = "List resources (deployments, services, nodes, …)")
public class GetCommand implements Callable<Integer> {

    @Parameters(index = "0",
        description = "Resource type: deployments, services, nodes, configmaps, secrets")
    String kind;

    @Option(names = {"-n", "--namespace"}) String namespace;
    @Option(names = {"-o", "--output"}, defaultValue = "table",
        description = "Output format: table (default) | json | wide")
    String output;
    @Option(names = {"--insecure", "-i"}) boolean insecure;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        var client = cfg.client(insecure);

        String path = "nodes".equals(kind)
            ? "/api/v1/cluster/nodes"
            : "/api/v1/namespaces/" + namespace + "/" + kind;

        var items = (List<?>) client.get(path, List.class);

        if ("json".equals(output)) {
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(items));
            return 0;
        }

        // Choose columns based on kind
        List<String> headers = switch (kind) {
            case "nodes"       -> List.of("NAME","IP","ROLE","STATUS","PRIMARY");
            case "deployments" -> List.of("NAME","NAMESPACE","REPLICAS","IMAGE");
            case "secrets"     -> List.of("NAME","NAMESPACE","APPLIED-BY");
            default            -> List.of("NAME","NAMESPACE","APPLIED-BY");
        };

        TablePrinter.print(headers, items.stream().map(i -> {
            var m = (Map<String,Object>) i;
            return switch (kind) {
                case "nodes"       -> List.of(s(m,"name"),s(m,"ip"),s(m,"role"),s(m,"status"),s(m,"primary"));
                case "deployments" -> List.of(s(m,"name"),s(m,"namespace"),
                    specVal(m,"replicas"), specVal(m,"image"));
                default            -> List.of(s(m,"name"),s(m,"namespace"),s(m,"appliedBy"));
            };
        }).toList());
        return 0;
    }

    private String s(Map<String,Object> m, String k) { return m.getOrDefault(k,"-").toString(); }

    @SuppressWarnings("unchecked")
    private String specVal(Map<String,Object> m, String key) {
        var spec = m.get("spec");
        if (spec instanceof Map) return ((Map<String,Object>)spec).getOrDefault(key,"-").toString();
        return "-";
    }
}
