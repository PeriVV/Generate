package MyUtils;

import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;

public class MethodExtractor {
    private static int startLine;
    private static int endLine;

    public static MethodDeclaration parseSelectedMethod(String code, String selectedMethodName) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(code);
        NodeList<TypeDeclaration<?>> types = cu.getTypes();
        for (TypeDeclaration<?> type : types) {
            NodeList<BodyDeclaration<?>> members = type.getMembers();
            for (BodyDeclaration<?> member : members) {
                if (member instanceof MethodDeclaration) {
                    MethodDeclaration methodDeclaration = (MethodDeclaration) member;
                    if (methodDeclaration.getName().getIdentifier().equals(selectedMethodName)) {
                        Range range = methodDeclaration.getRange().orElse(null);
                        if (range != null) {
                            startLine = range.begin.line;
                            endLine = range.end.line;
                        }
                        return methodDeclaration;
                    }
                }
            }
        }
        return null;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

}

