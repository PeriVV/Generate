package com.casc.unittestagent.util;

import com.intellij.execution.Platform;
import com.intellij.execution.configurations.GeneralCommandLine;

import java.util.ArrayList;
import java.util.List;

/** Retains IDEA's batch escaping while supporting cmd's long, fixed startup switches. */
public final class BuildToolCommandLine extends GeneralCommandLine {
    public BuildToolCommandLine(List<String> command) { super(command); }

    @Override
    protected List<String> prepareCommandLine(String executable, List<String> parameters, Platform platform) {
        String name = executable.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int execute = parameters.indexOf("/c");
        int delayed = parameters.indexOf("/v:off");
        if (platform == Platform.WINDOWS && name.equalsIgnoreCase("cmd.exe")
                && delayed >= 0 && execute > delayed) {
            // IDEA 222 treats a switch longer than two characters as the command itself,
            // quotes it, and loses the shell/batch escaping state for every later argument.
            var switches = new ArrayList<>(parameters);
            switches.remove(delayed);
            var prepared = new ArrayList<>(super.prepareCommandLine(executable, switches, platform));
            prepared.add(1, "/v:off");
            return prepared;
        }
        return super.prepareCommandLine(executable, parameters, platform);
    }
}
