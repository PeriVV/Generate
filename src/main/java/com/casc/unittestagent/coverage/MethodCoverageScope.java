package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.MethodModel;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Resolves executable ownership, rather than treating every bytecode method on nearby lines as the target. */
final class MethodCoverageScope {
    record Scope(IClassCoverage owner, List<IMethodCoverage> methods) {}
    private record Key(String name, String descriptor) {}

    static Scope resolve(CoverageBuilder coverage, String binaryName, MethodModel model, Path classes) throws IOException {
        IClassCoverage owner = coverage.getClasses().stream().filter(c -> c.getName().equals(binaryName))
                .findFirst().orElseThrow(() -> new IOException("编译产物中未找到被测类：" + binaryName));
        IMethodCoverage target = select(owner, model);
        if (target == null) {
            // Older symbol snapshots sometimes name the outer owner for a concrete nested method.
            // Recover only a unique concrete declaration; never fall back to all class lines.
            Scope recovered = null;
            for (IClassCoverage nested : coverage.getClasses()) {
                if (!nested.getName().startsWith(binaryName + "$")
                        || !Objects.equals(owner.getSourceFileName(), nested.getSourceFileName())) continue;
                IMethodCoverage candidate = select(nested, model);
                if (candidate == null) continue;
                if (recovered != null) throw new IOException("被测方法对应多个嵌套实现，无法唯一确定覆盖率范围。");
                recovered = new Scope(nested, List.of(candidate));
            }
            if (recovered == null) throw new IOException("无法唯一定位所选方法的可执行字节码；请选择具体实现方法重新分析。");
            owner = recovered.owner(); target = recovered.methods().get(0);
        }

        Map<Key, List<Key>> lambdas = new LinkedHashMap<>();
        Set<Key> synthetic = new HashSet<>();
        String ownerName = owner.getName();
        byte[] bytes = Files.readAllBytes(classes.resolve(ownerName + ".class"));
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                      String signature, String[] exceptions) {
                Key key = new Key(name, descriptor);
                if ((access & Opcodes.ACC_SYNTHETIC) != 0) synthetic.add(key);
                List<Key> edges = new ArrayList<>(); lambdas.put(key, edges);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                                Handle bootstrap, Object... arguments) {
                        if (!bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")) return;
                        for (Object argument : arguments) {
                            if (argument instanceof Handle implementation && implementation.getOwner().equals(ownerName)) {
                                edges.add(new Key(implementation.getName(), implementation.getDesc()));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<Key, IMethodCoverage> byKey = new LinkedHashMap<>();
        for (IMethodCoverage method : owner.getMethods()) byKey.put(new Key(method.getName(), method.getDesc()), method);
        Set<Key> included = new LinkedHashSet<>();
        Deque<Key> pending = new ArrayDeque<>(); pending.add(new Key(target.getName(), target.getDesc()));
        while (!pending.isEmpty()) {
            Key key = pending.removeFirst();
            if (!included.add(key)) continue;
            for (Key lambda : lambdas.getOrDefault(key, List.of())) {
                // Method references to ordinary helpers keep their own scope. Synthetic lambda bodies,
                // including serializable and nested lambdas, belong to the declaring executable.
                if (synthetic.contains(lambda) && lambda.name().startsWith("lambda$")) pending.add(lambda);
            }
        }
        List<IMethodCoverage> methods = new ArrayList<>();
        for (Key key : included) {
            IMethodCoverage method = byKey.get(key);
            if (method == null) throw new IOException("lambda 覆盖率字节码不完整，不能报告部分范围。");
            methods.add(method);
        }
        return new Scope(owner, List.copyOf(methods));
    }

    private static IMethodCoverage select(IClassCoverage owner, MethodModel model) throws IOException {
        IMethodCoverage best = null;
        int distance = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (IMethodCoverage candidate : owner.getMethods()) {
            if (!Objects.equals(model.getMethodName(), candidate.getName()) || candidate.getInstructionCounter().getTotalCount() == 0) continue;
            // A stable source signature supplies arity even when overloads share the same physical line.
            String identity = model.getMethodId();
            if (identity != null && identity.contains("(") && identity.endsWith(")")) {
                String parameters = identity.substring(identity.lastIndexOf('(') + 1, identity.length() - 1);
                int arity = parameters.isBlank() ? 0 : parameters.split(",", -1).length;
                if (Type.getArgumentTypes(candidate.getDesc()).length != arity) continue;
            }
            int first = candidate.getFirstLine(), last = candidate.getLastLine();
            if (first < 0 || last < model.getSourceStartLine() || first > model.getSourceEndLine()) continue;
            int next = Math.abs(first - model.getSourceStartLine()) + Math.abs(last - model.getSourceEndLine());
            if (next == distance) ambiguous = true;
            if (next < distance) { best = candidate; distance = next; ambiguous = false; }
        }
        if (ambiguous) throw new IOException("所选源码范围包含多个同名方法，无法唯一确定覆盖率范围。");
        return best;
    }
}
