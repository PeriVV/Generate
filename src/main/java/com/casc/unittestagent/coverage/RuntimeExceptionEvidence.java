package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.RuntimeDiagnostic;
import java.util.*;
import java.util.regex.Pattern;

/** Keeps the original throwable text; presentation limits must never change the observed root. */
final class RuntimeExceptionEvidence {
    private static final Pattern CAUSE = Pattern.compile("^Caused by:\\s*([\\w.$]+)(?::\\s?(.*))?$");
    private RuntimeExceptionEvidence() { }
    static void populate(RuntimeDiagnostic diagnostic) {
        var stack = new ArrayList<String>(); var chain = new ArrayList<String>(); var details = new ArrayList<String>();
        String rootType = diagnostic.getExceptionType(), rootMessage = diagnostic.getMessage();
        boolean rootContinuation = false, rootFramePending = true;
        for (String line : diagnostic.getFailureDetails().split("\\R", -1)) {
            String trimmed = line.strip();
            var cause = CAUSE.matcher(trimmed);
            if (cause.matches()) {
                details.add(line); stack.add(trimmed);
                // Throwable.printStackTrace indents suppressed branches, while the main causal chain
                // stays at column zero. A suppressed exception is evidence, not the main root cause.
                if (line.equals(line.stripLeading())) {
                    chain.add(cause.group(1)); rootType = cause.group(1);
                    rootMessage = Objects.requireNonNullElse(cause.group(2), "");
                    rootContinuation = true; rootFramePending = true;
                } else { rootContinuation = false; rootFramePending = false; }
            } else if (trimmed.startsWith("at ") || trimmed.startsWith("-> at ")) {
                stack.add(trimmed);
                if (rootFramePending) { diagnostic.setRootCauseFrame(trimmed); rootFramePending = false; }
                rootContinuation = false;
            } else if (trimmed.startsWith("Suppressed:") || trimmed.startsWith("... ")) {
                stack.add(trimmed); rootContinuation = false; rootFramePending = false;
            } else if (rootContinuation && !trimmed.isBlank()) {
                rootMessage += "\n" + line; // multiline exception messages are part of the actual cause
            }
        }
        diagnostic.setCauseChain(chain); diagnostic.setCauseDetails(details);
        diagnostic.setRootCauseType(rootType); diagnostic.setRootCauseMessage(rootMessage);
        diagnostic.setStackTrace(stack);
    }
}
