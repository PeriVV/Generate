package com.casc.unittestagent.model;

/**
 * 承载一次生成得到的测试文件信息。
 * 写文件模块使用文件名和内容落盘，预览对话框使用包名、类名和源码内容展示结果。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class GeneratedTestFile {
    /** 生成测试类需要复用的包名，保证测试文件与被测类处于同一包上下文。 */
    private String packageName;
    /** 被测类或生成测试类的简单类名，用于构造测试类名和实例名。 */
    private String className;
    /** 目标测试文件名，通常由被测类名追加 Test 后缀得到。 */
    private String fileName;
    /** 生成出的完整测试源码文本，预览和写文件操作都以它为准。 */
    private String content;

    /**
     * 读取 Java 文件包名。
     * 非 Java 文件返回空字符串，调用方可以保持简单的包名处理逻辑。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 包名文本
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * 更新生成测试类需要复用的包名，保证测试文件与被测类处于同一包上下文。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param packageName 包名文本
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * 读取被测类或生成测试类的简单类名，用于构造测试类名和实例名。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 类名文本
     */
    public String getClassName() {
        return className;
    }

    /**
     * 更新被测类或生成测试类的简单类名，用于构造测试类名和实例名。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param className 类名文本
     */
    public void setClassName(String className) {
        this.className = className;
    }

    /**
     * 读取目标测试文件名，通常由被测类名追加 Test 后缀得到。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 目标文件名
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * 更新目标测试文件名，通常由被测类名追加 Test 后缀得到。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param fileName 方法执行所需的输入参数
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * 读取生成出的完整测试源码文本，预览和写文件操作都以它为准。
     * 通过访问器暴露模型状态，避免调用方直接依赖字段实现。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 生成的测试源码内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 更新生成出的完整测试源码文本，预览和写文件操作都以它为准。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param content 方法执行所需的输入参数
     */
    public void setContent(String content) {
        this.content = content;
    }
}
