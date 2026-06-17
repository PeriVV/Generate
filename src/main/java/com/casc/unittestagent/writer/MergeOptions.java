package com.casc.unittestagent.writer;

/**
 * 控制测试类追加合并行为的选项。
 * 默认策略以保护已有测试为优先：替换同方法旧区域、合并 import 和 Mock 字段、重命名重复测试方法。
 */
public class MergeOptions {
    /** 同一被测方法已存在 UnitMind 区域时是否替换旧区域。 */
    private boolean replaceExistingRegionForSameMethod = true;
    /** 新测试方法与已有方法重名时是否自动追加递增后缀。 */
    private boolean renameDuplicatedTestMethods = true;
    /** 是否合并并去重 import。 */
    private boolean mergeImports = true;
    /** 是否合并并去重 Mock 与 InjectMocks 字段。 */
    private boolean mergeMockFields = true;

    /**
     * 判断是否替换同方法旧区域。
     *
     * @return 需要替换旧区域时返回 true
     */
    public boolean isReplaceExistingRegionForSameMethod() {
        return replaceExistingRegionForSameMethod;
    }

    /**
     * 设置是否替换同方法旧区域。
     *
     * @param replaceExistingRegionForSameMethod 需要替换旧区域时传入 true
     */
    public void setReplaceExistingRegionForSameMethod(boolean replaceExistingRegionForSameMethod) {
        this.replaceExistingRegionForSameMethod = replaceExistingRegionForSameMethod;
    }

    /**
     * 判断是否重命名重复测试方法。
     *
     * @return 需要自动重命名时返回 true
     */
    public boolean isRenameDuplicatedTestMethods() {
        return renameDuplicatedTestMethods;
    }

    /**
     * 设置是否重命名重复测试方法。
     *
     * @param renameDuplicatedTestMethods 需要自动重命名时传入 true
     */
    public void setRenameDuplicatedTestMethods(boolean renameDuplicatedTestMethods) {
        this.renameDuplicatedTestMethods = renameDuplicatedTestMethods;
    }

    /**
     * 判断是否合并 import。
     *
     * @return 需要合并 import 时返回 true
     */
    public boolean isMergeImports() {
        return mergeImports;
    }

    /**
     * 设置是否合并 import。
     *
     * @param mergeImports 需要合并 import 时传入 true
     */
    public void setMergeImports(boolean mergeImports) {
        this.mergeImports = mergeImports;
    }

    /**
     * 判断是否合并 Mock 字段。
     *
     * @return 需要合并 Mock 字段时返回 true
     */
    public boolean isMergeMockFields() {
        return mergeMockFields;
    }

    /**
     * 设置是否合并 Mock 字段。
     *
     * @param mergeMockFields 需要合并 Mock 字段时传入 true
     */
    public void setMergeMockFields(boolean mergeMockFields) {
        this.mergeMockFields = mergeMockFields;
    }
}
