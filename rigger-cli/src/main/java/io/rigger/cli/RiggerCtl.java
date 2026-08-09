package io.rigger.cli;

/** Entry point — delegates to Picocli command tree. */
public class RiggerCtl {
    public static void main(String[] args) {
        io.rigger.cli.command.RiggerCtl.main(args);
    }
}
