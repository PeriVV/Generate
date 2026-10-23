package com.casc.unittestagent.validation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import java.util.*;

/** Whole-member subset projection. Surviving tests, fields, constructors and lifecycle bodies are immutable. */
public final class TestSubsetProjection {
    public record Result(String code, List<String> removedHelpers) {}
    private TestSubsetProjection() {}

    public static Result project(String original, Set<String> retained) {
        // Also rejects malformed generated regions before authorizing any removal.
        JUnitTestInventory.methods(original);
        var unit=StaticJavaParser.parse(original); LexicalPreservingPrinter.setup(unit);
        List<MethodDeclaration> all=unit.findAll(MethodDeclaration.class);
        Set<MethodDeclaration> removed=identitySet();
        for(var method:all) if(JUnitTestInventory.isTest(unit,method) && !retained.contains(id(unit,method))) removed.add(method);
        if(removed.isEmpty()) return new Result(original,List.of());
        Set<MethodDeclaration> helpers=identitySet();
        for(var method:all) if(eligible(unit,method)) helpers.add(method);
        // A helper must have belonged to an excluded test, not merely be apparently unused.
        Set<MethodDeclaration> formerlyUsed=reachable(removed,all);
        Set<MethodDeclaration> candidates=identitySet();
        helpers.stream().filter(formerlyUsed::contains).forEach(candidates::add);
        removed.forEach(Node::remove);
        Set<MethodDeclaration> live=reachable(roots(unit,candidates),unit.findAll(MethodDeclaration.class));
        List<String> pruned=new ArrayList<>();
        List<Node> possibleLookups=new ArrayList<>(roots(unit,candidates)); possibleLookups.addAll(live);
        if(!opaqueMethodLookup(possibleLookups)) for(var method:all) {
            if(candidates.contains(method) && !live.contains(method)) {
                pruned.add(id(unit,method)); method.remove();
            }
        }
        trimImports(unit);
        return new Result(LexicalPreservingPrinter.print(unit),List.copyOf(pruned));
    }

    /** Used by merge protection: helper groups cannot disappear if any retained member may call them. */
    public static boolean unreferenced(CompilationUnit unit, Collection<MethodDeclaration> helpers) {
        Set<MethodDeclaration> group=identitySet(); group.addAll(helpers);
        List<Node> roots=roots(unit,group);
        if(opaqueMethodLookup(roots)) return false;
        Set<MethodDeclaration> live=reachable(roots,unit.findAll(MethodDeclaration.class));
        return group.stream().noneMatch(live::contains);
    }

    private static boolean eligible(CompilationUnit unit,MethodDeclaration method) {
        return method.isPrivate() && method.getAnnotations().isEmpty() && method.getBody().isPresent()
                && !JUnitTestInventory.isTest(unit,method) && JUnitTestInventory.isRemovableMember(unit,method);
    }
    private static List<Node> roots(CompilationUnit unit,Set<MethodDeclaration> candidates) {
        List<Node> roots=new ArrayList<>();
        unit.findAll(MethodDeclaration.class).stream().filter(m -> !candidates.contains(m)).forEach(roots::add);
        // An enclosing method/root may contain a nested declaration: over-retaining is intentional.
        unit.findAll(FieldDeclaration.class).forEach(roots::add);
        unit.findAll(ConstructorDeclaration.class).forEach(roots::add);
        unit.findAll(InitializerDeclaration.class).forEach(roots::add);
        unit.findAll(AnnotationExpr.class).stream().filter(a -> a.getParentNode().orElse(null) instanceof TypeDeclaration).forEach(roots::add);
        return roots;
    }
    private static Set<MethodDeclaration> reachable(Collection<? extends Node> roots,List<MethodDeclaration> methods) {
        Map<String,List<MethodDeclaration>> byName=new HashMap<>();
        methods.forEach(m -> byName.computeIfAbsent(m.getNameAsString(),ignored -> new ArrayList<>()).add(m));
        Set<MethodDeclaration> reached=identitySet();
        ArrayDeque<Node> queue=new ArrayDeque<>(roots);
        while(!queue.isEmpty()) {
            Node node=queue.remove();
            Set<String> names=new HashSet<>();
            node.findAll(MethodCallExpr.class).forEach(c -> names.add(c.getNameAsString()));
            node.findAll(MethodReferenceExpr.class).forEach(c -> names.add(c.getIdentifier()));
            // MethodSource, reflection with literal names and custom named providers are roots too.
            for(var literal:node.findAll(StringLiteralExpr.class)) for(String name:byName.keySet())
                if(java.util.regex.Pattern.compile("(?<![\\p{Alnum}_$])"+java.util.regex.Pattern.quote(name)+"(?![\\p{Alnum}_$])").matcher(literal.asString()).find()) names.add(name);
            if(node instanceof MethodDeclaration method) {
                // JUnit MethodSource's empty value refers to a same-name provider.
                if(JUnitTestInventory.isTest(method.findCompilationUnit().orElseThrow(),method)) names.add(method.getNameAsString());
            }
            for(String name:names) for(var target:byName.getOrDefault(name,List.of()))
                if(reached.add(target)) queue.add(target);
        }
        return reached;
    }
    private static boolean opaqueMethodLookup(Collection<? extends Node> roots) {
        return roots.stream().flatMap(n -> n.findAll(MethodCallExpr.class).stream()).anyMatch(c ->
                Set.of("getDeclaredMethod","getDeclaredMethods","getMethod","getMethods","findVirtual","findStatic","findSpecial","unreflect",
                        "invoke","invokeExact","invokeWithArguments","invokeMethod").contains(c.getNameAsString()));
    }
    public static String id(CompilationUnit unit,MethodDeclaration method) {
        return unit.getPackageDeclaration().map(p -> p.getNameAsString()+".").orElse("")
                +JUnitTestInventory.owner(method)+"#"+method.getSignature().asString();
    }
    public static void trimImports(CompilationUnit unit) {
        Set<String> used=new HashSet<>();
        unit.findAll(SimpleName.class).stream().filter(n -> n.findAncestor(ImportDeclaration.class).isEmpty()).forEach(n -> used.add(n.getIdentifier()));
        unit.findAll(AnnotationExpr.class).forEach(a -> used.add(a.getName().getIdentifier()));
        for(var imported:new ArrayList<>(unit.getImports())) if(!imported.isAsterisk() && !used.contains(imported.getName().getIdentifier())) imported.remove();
    }
    private static Set<MethodDeclaration> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
}
