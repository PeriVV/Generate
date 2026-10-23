package com.casc.unittestagent.coverage;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent;
import org.jetbrains.idea.maven.server.MavenDistributionsCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read the Maven installation and repository settings used by this IDEA project. */
final class IdeMavenBuildConfiguration {
    record Configuration(String executable, List<String> arguments) { }

    static Configuration resolve(Project project, Path module, boolean installation) throws java.io.IOException {
        if (project == null) return null;
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.idea.maven"));
        if (plugin == null || !plugin.isEnabled()) return null;
        return read(project, module, installation);
    }

    // Keep Maven-plugin references behind the optional-plugin check.
    private static Configuration read(Project project, Path module, boolean installation) throws java.io.IOException {
        var settings = MavenWorkspaceSettingsComponent.getInstance(project).getSettings().getGeneralSettings();
        String executable = "";
        if (installation) {
            var distribution = MavenDistributionsCache.getInstance(project).getMavenDistribution(module.toString());
            Path binary = distribution.getMavenHome().resolve(BuildConfigurationProcess.isWindows() ? "bin/mvn.cmd" : "bin/mvn");
            if (!distribution.isValid() || !Files.isRegularFile(binary)) {
                throw new java.io.IOException("IDEA 配置的 Maven 尚不可用，请先在 Maven 窗口完成 Wrapper 安装或检查 Maven 主目录。");
            }
            executable = binary.toString();
        }
        List<String> arguments = new ArrayList<>();
        if (!settings.getUserSettingsFile().isBlank()) arguments.addAll(List.of("--settings", settings.getUserSettingsFile()));
        if (!settings.getLocalRepository().isBlank()) arguments.add("-Dmaven.repo.local=" + settings.getLocalRepository());
        if (settings.isWorkOffline()) arguments.add("--offline");
        return new Configuration(executable, List.copyOf(arguments));
    }
}
