package io.rigger.cli.command.cluster;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/** riggerctl cluster <up|sync|down|status> */
@Command(name = "cluster", description = "Manage cluster lifecycle",
         subcommands = {ClusterUpCommand.class, ClusterSyncCommand.class, ClusterStatusCommand.class})
public class ClusterCommand implements Callable<Integer> {
    @Override public Integer call() { CommandLine.usage(this, System.out); return 0; }
}
