package io.rigger.cli.command.workload;

import io.rigger.cli.config.CliConfig;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/** riggerctl delete deployment payments-api -n prod */
@Command(name = "delete", description = "Delete a resource from the cluster")
public class DeleteCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Resource type (deployment, service, configmap, secret)") String kind;
    @Parameters(index = "1", description = "Resource name") String name;
    @Option(names = {"-n", "--namespace"}) String namespace;
    @Option(names = {"--insecure", "-i"}) boolean insecure;

    @Override
    public Integer call() throws Exception {
        var cfg = CliConfig.load();
        if (namespace == null) namespace = cfg.defaultNamespace();
        if (!confirmDelete(kind, name, namespace)) { System.out.println("Aborted."); return 0; }
        cfg.client(insecure).delete("/api/v1/namespaces/" + namespace + "/" + kind + "s/" + name);
        System.out.println("✓ Deleted " + kind + "/" + name);
        return 0;
    }

    private boolean confirmDelete(String kind, String name, String ns) {
        System.out.print("Delete " + kind + " '" + name + "' in namespace '" + ns + "'? [y/N] ");
        var answer = System.console() != null
            ? System.console().readLine()
            : new java.util.Scanner(System.in).nextLine();
        return "y".equalsIgnoreCase(answer != null ? answer.trim() : "");
    }
}
