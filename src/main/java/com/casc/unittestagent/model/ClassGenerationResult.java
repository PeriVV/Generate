package com.casc.unittestagent.model;

import java.util.ArrayList;
import java.util.List;

/** 整类测试生成结果，同时保留 GenerationResult 以复用预览、验证和写入门禁。 */
public class ClassGenerationResult extends GenerationResult {
    private ClassModel classModel;
    private List<MethodGenerationItem> methodItems = new ArrayList<>();
    private boolean cancelled;
    private boolean complete;

    public ClassGenerationResult() {
        setGenerationScope(GenerationScope.CLASS);
    }

    public ClassModel getClassModel() { return classModel; }
    public void setClassModel(ClassModel classModel) { this.classModel = classModel; }
    public List<MethodGenerationItem> getMethodItems() { return methodItems; }
    public void setMethodItems(List<MethodGenerationItem> methodItems) { this.methodItems = methodItems == null ? new ArrayList<>() : methodItems; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
}
