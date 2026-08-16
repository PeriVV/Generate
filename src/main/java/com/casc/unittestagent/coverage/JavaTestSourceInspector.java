package com.casc.unittestagent.coverage;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/** Reads the declared package and top-level type names from a candidate test source. */
public final class JavaTestSourceInspector {
    private JavaTestSourceInspector() { }

    public static Inspection inspect(String source) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        ParseResult<CompilationUnit> parsed = new JavaParser(configuration).parse(
                ParseStart.COMPILATION_UNIT, Providers.provider(source == null ? "" : source));
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return new Inspection(false, "", new ArrayList<>());
        }
        CompilationUnit unit = parsed.getResult().get();
        List<String> types = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) types.add(type.getNameAsString());
        return new Inspection(true,
                unit.getPackageDeclaration().map(item -> item.getNameAsString()).orElse(""), types);
    }

    public static final class Inspection {
        private final boolean parsed;
        private final String packageName;
        private final List<String> topLevelTypes;

        private Inspection(boolean parsed, String packageName, List<String> topLevelTypes) {
            this.parsed = parsed;
            this.packageName = packageName;
            this.topLevelTypes = topLevelTypes;
        }

        public boolean isParsed() { return parsed; }
        public String getPackageName() { return packageName; }
        public List<String> getTopLevelTypes() { return topLevelTypes; }
        public boolean declares(String className) { return topLevelTypes.contains(className); }
    }
}
