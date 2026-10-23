package com.casc.unittestagent.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.*;

/** Add only source-proven unresolved type imports. Never guesses by global simple-name search. */
public final class SourceImportPreflight {
    public record Result(String code, List<String> imports) { public boolean changed() { return !imports.isEmpty(); } }
    private SourceImportPreflight() { }

    public static Result prepare(Project project, PsiFile source, String code) {
        if (project == null || source == null || code == null || code.isBlank()) return new Result(code, List.of());
        return ReadAction.compute(() -> {
            if (!(source instanceof PsiJavaFile production) || DumbService.isDumb(project)) return new Result(code, List.of());
            try { return complete(project, production, code); }
            catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) { throw cancelled; }
            catch (RuntimeException unresolved) { return new Result(code, List.of()); }
        });
    }

    private static Result complete(Project project, PsiJavaFile source, String code) {
        var unit = StaticJavaParser.parse(code);
        var sourceImports = source.getImportList();
        if (sourceImports == null || sourceImports.getImportStatements().length == 0) return new Result(code, List.of());
        var module = source.getVirtualFile() == null ? null : ProjectFileIndex.getInstance(project).getModuleForFile(source.getVirtualFile());
        var scope = module == null ? GlobalSearchScope.projectScope(project)
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, true);
        var facade = JavaPsiFacade.getInstance(project);
        var test = PsiFileFactory.getInstance(project).createFileFromText("UnitMindImportProbe.java", JavaFileType.INSTANCE, code);
        Map<String, Set<String>> evidence = new LinkedHashMap<>();
        for (PsiImportStatement imported : sourceImports.getImportStatements()) {
            if (imported.isOnDemand() || !(imported.resolve() instanceof PsiClass owner) || owner.getQualifiedName() == null) continue;
            // Verify the exact source binding in the current module, including duplicate FQNs.
            var candidates = facade.findClasses(owner.getQualifiedName(), scope);
            if (candidates.length != 1 || !facade.getResolveHelper().isAccessible(owner, test, null)) continue;
            evidence.computeIfAbsent(owner.getName(), ignored -> new LinkedHashSet<>()).add(owner.getQualifiedName());
        }
        Set<String> references = new LinkedHashSet<>();
        unit.findAll(ClassOrInterfaceType.class).stream().filter(t -> t.getScope().isEmpty())
                .forEach(type -> references.add(type.getNameAsString()));
        Set<String> declared = new HashSet<>();
        unit.findAll(TypeDeclaration.class).forEach(t -> declared.add(t.getNameAsString()));
        unit.findAll(TypeParameter.class).forEach(t -> declared.add(t.getNameAsString()));
        var testReferences = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(test, PsiJavaCodeReferenceElement.class);
        List<String> added = new ArrayList<>();
        for (String name : references) {
            var owners = evidence.getOrDefault(name, Set.of());
            if (owners.size() != 1 || declared.contains(name)) continue;
            String qualified = owners.iterator().next();
            if (unit.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk() && name.equals(i.getName().getIdentifier()))) continue;
            String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (facade.findClasses((pkg.isBlank() ? "" : pkg + ".") + name, scope).length > 0
                    || facade.findClasses("java.lang." + name, scope).length > 0 || implicitJavaLang(name)) continue;
            boolean conflictingWildcard = unit.getImports().stream().filter(i -> !i.isStatic() && i.isAsterisk())
                    .anyMatch(i -> Arrays.stream(facade.findClasses(i.getNameAsString() + "." + name, scope))
                            .anyMatch(c -> !qualified.equals(c.getQualifiedName())));
            if (conflictingWildcard) continue;
            // Existing test bindings win. An unresolved name alone is not sufficient:
            // the exact production import above is also required.
            if (testReferences.stream().anyMatch(reference -> name.equals(reference.getReferenceName())
                    && reference.resolve() instanceof PsiClass)) continue;
            added.add(qualified);
        }
        if (added.isEmpty()) return new Result(code, List.of());
        LexicalPreservingPrinter.setup(unit); added.forEach(unit::addImport);
        return new Result(LexicalPreservingPrinter.print(unit), List.copyOf(added));
    }

    private static boolean implicitJavaLang(String name) {
        // Remain conservative even with an incomplete SDK index. A newer plugin JDK
        // may cause a missed completion on an older project JDK, never a wrong binding.
        try { Class.forName("java.lang." + name, false, null); return true; }
        catch (ClassNotFoundException absent) { return false; }
    }

    /** Import-only model edits cannot accidentally erase still-used imports from the baseline. */
    public static Result preserveImportOnlyPatch(String before, String proposed) {
        try {
            var baseline = StaticJavaParser.parse(before); var candidate = StaticJavaParser.parse(proposed);
            if (!body(baseline).equals(body(candidate))) return new Result(proposed, List.of());
            var used = new HashSet<String>(); candidate.findAll(SimpleName.class).forEach(n -> used.add(n.getIdentifier()));
            candidate.findAll(AnnotationExpr.class).forEach(a -> used.add(a.getName().getIdentifier()));
            var additions = new ArrayList<com.github.javaparser.ast.ImportDeclaration>();
            for (var imported : baseline.getImports()) {
                if (candidate.getImports().stream().anyMatch(i -> i.equals(imported))) continue;
                if (imported.isAsterisk()) {
                    if (!potentialWildcardUse(candidate, imported.isStatic())) continue;
                    // Avoid resolving conflicts between different wildcard owners by guessing.
                    if (candidate.getImports().stream().anyMatch(i -> i.isStatic() == imported.isStatic() && i.isAsterisk()
                            && !baseline.getImports().contains(i))) continue;
                } else {
                    if (!used.contains(imported.getName().getIdentifier())) continue;
                    if (candidate.getImports().stream().anyMatch(i -> i.isStatic() == imported.isStatic() && !i.isAsterisk()
                            && i.getName().getIdentifier().equals(imported.getName().getIdentifier()))) continue;
                }
                additions.add(imported.clone());
            }
            if (additions.isEmpty()) return new Result(proposed, List.of());
            LexicalPreservingPrinter.setup(candidate); additions.forEach(candidate::addImport);
            return new Result(LexicalPreservingPrinter.print(candidate), additions.stream().map(Object::toString).toList());
        } catch (RuntimeException invalid) { return new Result(proposed, List.of()); }
    }

    private static String body(CompilationUnit original) {
        var unit = original.clone(); unit.getImports().clear(); unit.getAllContainedComments().forEach(c -> c.remove());
        unit.removeComment(); return unit.toString();
    }

    private static boolean potentialWildcardUse(CompilationUnit unit, boolean staticImport) {
        if (!staticImport) return unit.findAll(ClassOrInterfaceType.class).stream()
                .filter(type -> type.getScope().isEmpty() && !implicitJavaLang(type.getNameAsString()))
                .anyMatch(type -> unit.getImports().stream().noneMatch(i -> !i.isStatic() && !i.isAsterisk()
                        && i.getName().getIdentifier().equals(type.getNameAsString())));
        boolean freeCall = unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(call -> call.getScope().isEmpty())
                .filter(call -> unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                        .noneMatch(method -> method.getNameAsString().equals(call.getNameAsString())))
                .anyMatch(call -> unit.getImports().stream().noneMatch(i -> i.isStatic() && !i.isAsterisk()
                        && i.getName().getIdentifier().equals(call.getNameAsString())));
        if (freeCall) return true;
        Set<String> variables = new HashSet<>();
        unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(v -> variables.add(v.getNameAsString()));
        unit.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(p -> variables.add(p.getNameAsString()));
        return unit.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                .filter(name -> !variables.contains(name.getNameAsString()) && !implicitJavaLang(name.getNameAsString()))
                .anyMatch(name -> unit.getImports().stream().noneMatch(i -> !i.isAsterisk()
                        && i.getName().getIdentifier().equals(name.getNameAsString())));
    }
}
