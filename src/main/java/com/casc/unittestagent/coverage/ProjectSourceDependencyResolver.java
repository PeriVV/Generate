package com.casc.unittestagent.coverage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Finds project source files that javac must receive explicitly for annotation processing. */
public final class ProjectSourceDependencyResolver {
    private static final int MAX_SOURCES = 512;

    private ProjectSourceDependencyResolver() { }

    public static List<File> resolve(Iterable<File> sourceRoots, String... sourceTexts) {
        List<File> roots = new ArrayList<>();
        for (File root : sourceRoots) {
            if (root != null && root.isDirectory()) roots.add(root);
        }
        Set<File> result = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        if (sourceTexts != null) {
            for (String text : sourceTexts) {
                if (text != null && !text.isBlank()) pending.addLast(text);
            }
        }
        while (!pending.isEmpty() && result.size() < MAX_SOURCES) {
            String text = pending.removeFirst();
            CompilationUnit unit;
            try {
                unit = StaticJavaParser.parse(text);
            } catch (RuntimeException ignored) {
                continue;
            }
            for (ImportDeclaration declaration : unit.getImports()) {
                if (declaration.isAsterisk()) {
                    addPackageSources(roots, declaration.getNameAsString(), text, result, pending);
                } else {
                    addTypeSource(roots, declaration.getNameAsString(), result, pending);
                }
                if (result.size() >= MAX_SOURCES) break;
            }
            unit.getPackageDeclaration().ifPresent(declaration ->
                    addPackageSources(roots, declaration.getNameAsString(), text, result, pending));
        }
        return new ArrayList<>(result);
    }

    public static boolean isSourceFile(File candidate,
                                       Iterable<File> sourceRoots,
                                       String packageName,
                                       String fileName) {
        if (candidate == null || fileName == null || fileName.isBlank()) return false;
        String relativePackage = packageName == null ? "" : packageName.replace('.', File.separatorChar);
        File normalizedCandidate = normalize(candidate);
        for (File root : sourceRoots) {
            if (root == null) continue;
            File expected = relativePackage.isBlank()
                    ? new File(root, fileName) : new File(new File(root, relativePackage), fileName);
            if (normalize(expected).equals(normalizedCandidate)) return true;
        }
        return false;
    }

    private static void addTypeSource(List<File> roots,
                                      String qualifiedName,
                                      Set<File> result,
                                      Deque<String> pending) {
        String candidate = qualifiedName;
        while (candidate != null && candidate.contains(".")) {
            String relative = candidate.replace('.', File.separatorChar) + ".java";
            for (File root : roots) {
                File source = new File(root, relative);
                if (source.isFile()) {
                    addSource(source, result, pending);
                    return;
                }
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
    }

    private static void addPackageSources(List<File> roots,
                                          String packageName,
                                          String referringText,
                                          Set<File> result,
                                          Deque<String> pending) {
        String relative = packageName.replace('.', File.separatorChar);
        for (File root : roots) {
            File directory = new File(root, relative);
            if (!directory.isDirectory()) continue;
            try (Stream<java.nio.file.Path> paths = Files.list(directory.toPath())) {
                paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> containsIdentifier(referringText,
                                path.getFileName().toString().replaceFirst("\\.java$", "")))
                        .limit(MAX_SOURCES - result.size())
                        .forEach(path -> addSource(path.toFile(), result, pending));
            } catch (IOException ignored) {
                // An unreadable optional source root is left for javac to diagnose if referenced.
            }
        }
    }

    private static boolean containsIdentifier(String text, String identifier) {
        return text != null && identifier != null && !identifier.isBlank()
                && Pattern.compile("(?<![\\p{Alnum}_$])" + Pattern.quote(identifier)
                + "(?![\\p{Alnum}_$])").matcher(text).find();
    }

    private static void addSource(File source, Set<File> result, Deque<String> pending) {
        // Keep the IDE-visible path: a canonical symlink alias may have different encoding settings.
        File normalized = source.toPath().toAbsolutePath().normalize().toFile();
        if (!result.add(normalized)) return;
        try {
            pending.addLast(com.casc.unittestagent.util.ProjectFileText.read(normalized.toPath()));
        } catch (IOException ignored) {
            // Keep the source in the explicit list; javac will provide the actionable error.
        }
    }

    private static File normalize(File source) {
        try {
            return source.getCanonicalFile();
        } catch (IOException ignored) {
            return source.getAbsoluteFile();
        }
    }
}
