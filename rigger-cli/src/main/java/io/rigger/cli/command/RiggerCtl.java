package io.rigger.cli.command;

import io.rigger.cli.command.cluster.*;
import io.rigger.cli.command.workload.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

/**
 * riggerctl — root Picocli command.
 *
 * Usage workflow:
 *   1. riggerctl init   --server https://host:7433 [--insecure]
 *   2. riggerctl login  -u admin -p admin           [--insecure]
 *   3. riggerctl get nodes
 *   4. riggerctl apply -f deployment.yaml -n production
 */
@Command(
    name = "riggerctl",
    description = {
        "Rigger cluster operator CLI",
        "",
        "Quick start:",
        "  riggerctl init  --server https://host:7433 --insecure",
        "  riggerctl login -u admin [-p password]    --insecure",
        "  riggerctl get nodes",
    },
    version = "1.0.0",
    mixinStandardHelpOptions = true,
    subcommands = {
        InitCommand.class,
        LoginCommand.class,
        WhoAmICommand.class,
        UserCommand.class,
        ClusterCommand.class,
        ApplyCommand.class,
        ConvertCommand.class,
        GetCommand.class,
        DeleteCommand.class,
        ScaleCommand.class,
        LogsCommand.class,
    }
)
public class RiggerCtl implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new RiggerCtl())
            .setExecutionExceptionHandler((ex, cmd, pr) -> {
                System.err.println("Error: " + ex.getMessage());
                if (ex.getMessage() != null && ex.getMessage().contains("Not authenticated")) {
                    System.err.println("Run: riggerctl login -u admin");
                }
                return 1;
            })
            .execute(args);
        System.exit(exit);
    }
}
