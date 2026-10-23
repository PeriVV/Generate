package com.casc.unittestagent.util;

import com.intellij.execution.CommandLineUtil;
import com.intellij.execution.Platform;
import com.intellij.execution.configurations.GeneralCommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A native batch entry point keeps arguments literal across cmd's parsing passes. */
public final class BuildToolCommandLine extends GeneralCommandLine {
    private final List<String> original;

    public BuildToolCommandLine(List<String> command) {
        super(command);
        original = List.copyOf(command);
    }

    @Override
    protected Process startProcess(List<String> preparedCommand) throws IOException {
        int execute = original.indexOf("/c");
        String executable = original.get(0).replace('\\', '/');
        String target = execute >= 0 && execute + 1 < original.size() ? original.get(execute + 1) : "";
        String lower = target.toLowerCase(Locale.ROOT);
        if (Platform.current() != Platform.WINDOWS || !executable.toLowerCase(Locale.ROOT).endsWith("cmd.exe")
                || !(lower.endsWith(".bat") || lower.endsWith(".cmd"))) {
            return super.startProcess(preparedCommand);
        }
        Path directory = Files.createTempDirectory("unitmind-build-launch-");
        Path script = directory.resolve("launch.cmd");
        String prefix = "UNITMIND_ARG_" + UUID.randomUUID().toString().replace("-", "") + "_";
        var arguments = CommandLineUtil.toCommandLine(target, original.subList(execute + 2, original.size()), Platform.WINDOWS);
        var text = new StringBuilder("@echo off\r\nsetlocal DisableDelayedExpansion\r\n");
        for (int index = 0; index < arguments.size(); index++) {
            String variable = prefix + index;
            // Expanding a Unicode environment value once does not recursively expand a literal
            // %NAME% inside it. No argument values are persisted in the temporary script.
            withEnvironment(variable, index == 0 ? '"' + target + '"' : arguments.get(index));
            if (index > 0) text.append(' ');
            text.append('%').append(variable).append('%');
        }
        text.append("\r\n");
        try {
            Files.writeString(script, text, StandardCharsets.US_ASCII);
            Process process = super.startProcess(List.of(original.get(0), "/d", "/v:off", "/s", "/c", "\"\"" + script + "\"\""));
            process.onExit().thenRun(() -> cleanup(script, directory));
            return process;
        } catch (IOException | RuntimeException error) {
            cleanup(script, directory);
            throw error;
        } finally {
            for (int index = 0; index < arguments.size(); index++) getEnvironment().remove(prefix + index);
        }
    }

    private static void cleanup(Path script, Path directory) {
        try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
    }
}
