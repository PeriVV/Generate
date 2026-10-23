package com.casc.unittestagent.service;

import com.casc.unittestagent.coverage.*;
import java.util.Objects;

/** Injectable execution boundary; each generation task owns its environment-export session. */
public record TestExecutionServices(ProjectTestEnvironmentResolver environments, TestSandboxCompiler compiler,
                                    JacocoCoverageExecutor executor) {
    public TestExecutionServices {
        Objects.requireNonNull(environments); Objects.requireNonNull(compiler); Objects.requireNonNull(executor);
    }
    public static TestExecutionServices defaults() {
        return new TestExecutionServices(new ProjectTestEnvironmentResolver(), new TestSandboxCompiler(), new JacocoCoverageExecutor());
    }
}
