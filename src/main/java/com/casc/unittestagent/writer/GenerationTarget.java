package com.casc.unittestagent.writer;

import com.casc.unittestagent.model.GenerationScope;

/** 写入合并时用于定位 UnitMind region 的稳定目标。 */
public class GenerationTarget {
    private final GenerationScope scope;
    private final String key;

    private GenerationTarget(GenerationScope scope, String key) {
        this.scope = scope;
        this.key = key == null ? "" : key.trim();
    }

    public static GenerationTarget method(String methodName) {
        return new GenerationTarget(GenerationScope.METHOD, methodName);
    }

    public static GenerationTarget classTarget(String qualifiedClassName) {
        return new GenerationTarget(GenerationScope.CLASS, qualifiedClassName);
    }

    public GenerationScope getScope() { return scope; }
    public String getKey() { return key; }
    public String regionLabel() { return scope == GenerationScope.CLASS ? "class " + key : key; }
}
