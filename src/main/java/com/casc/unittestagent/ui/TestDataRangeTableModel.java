package com.casc.unittestagent.ui;

import com.casc.unittestagent.model.TestDataRangeConfig;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 把测试数据范围配置适配成 Swing 表格模型。
 * 该模型负责列展示、编辑权限、用户输入解析和行级校验，是配置对话框的主要业务载体。
 * 它与同包内的模型、生成器或 UI 组件协作，但自身只保留当前层级需要承担的职责。
 */
public class TestDataRangeTableModel extends AbstractTableModel {
    /** 配置表格的列名定义，顺序需要和 getValueAt、setValueAt 保持一致。 */
    private final String[] columns = {"类型", "最小值", "最大值", "小数位数", "字符串规则", "候选值"};
    /** 当前进程内生效的测试数据范围配置列表。 */
    private List<TestDataRangeConfig> configs;

    /**
     * 创建测试数据范围表格模型。
     * 构造阶段复制配置列表，防止表格编辑直接修改管理器持有的运行态对象。
     *
     * 异常情况：无主动抛出的受检异常。
     *
     * @param configs 要展示或替换的测试数据范围配置列表
     */
    public TestDataRangeTableModel(List<TestDataRangeConfig> configs) {
        this.configs = new ArrayList<>(configs);
    }

    /**
     * 返回配置表格的行数。
     * Swing 表格根据该值决定需要展示多少条类型配置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 配置表格行数
     */
    @Override
    public int getRowCount() {
        return configs.size();
    }

    /**
     * 返回配置表格的列数。
     * 列数与 columns 数组保持一致，保证展示和编辑逻辑使用同一结构。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 配置表格列数
     */
    @Override
    public int getColumnCount() {
        return columns.length;
    }

    /**
     * 返回指定列的表头名称。
     * 表头用于帮助用户理解该列配置项的业务含义。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param column 表格列索引
     * @return 指定列的展示名称
     */
    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    /**
     * 返回指定单元格的展示值。
     * 空值会显示为短横线，避免用户误以为空白单元格代表未加载。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param rowIndex 表格行索引
     * @param columnIndex 表格列索引
     * @return 指定单元格的展示值
     */
    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return config.getTypeName();
            case 1:
                return valueOrDash(config.getMinValue());
            case 2:
                return valueOrDash(config.getMaxValue());
            case 3:
                return config.getDecimalScale() == null ? "-" : config.getDecimalScale();
            case 4:
                return valueOrDash(config.getStringRegex());
            case 5:
                return valueOrDash(config.getCandidateValues());
            default:
                return "";
        }
    }

    /**
     * 判断指定单元格是否允许编辑。
     * 不同类型只开放有意义的列，避免用户为字符串配置数值边界或为整数配置小数位。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param rowIndex 表格行索引
     * @param columnIndex 表格列索引
     * @return 如果指定单元格允许编辑则返回 true
     */
    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        if (columnIndex == 0) {
            return false;
        }
        if (config.isStringType()) {
            return columnIndex == 4;
        }
        if (config.isCandidateType()) {
            return columnIndex == 5;
        }
        if (config.isFloatingType()) {
            return columnIndex == 1 || columnIndex == 2 || columnIndex == 3;
        }
        return columnIndex == 1 || columnIndex == 2;
    }

    /**
     * 接收并解析用户编辑的单元格值。
     * 解析失败的小数位会暂存为非法值，后续统一由 validateRows 给出用户可读错误。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param value 用户在表格中输入的新值
     * @param rowIndex 表格行索引
     * @param columnIndex 表格列索引
     */
    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        TestDataRangeConfig config = configs.get(rowIndex);
        String text = value == null ? "" : value.toString().trim();
        switch (columnIndex) {
            case 1:
                config.setMinValue(text);
                break;
            case 2:
                config.setMaxValue(text);
                break;
            case 3:
                if ("-".equals(text) || text.isBlank()) {
                    config.setDecimalScale(null);
                } else {
                    try {
                        config.setDecimalScale(Integer.parseInt(text));
                    } catch (NumberFormatException ex) {
                        config.setDecimalScale(-1);
                    }
                }
                break;
            case 4:
                config.setStringRegex(text);
                break;
            case 5:
                config.setCandidateValues(text);
                break;
            default:
                break;
        }
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    /**
     * 读取当前测试数据范围配置。
     * 返回副本而不是内部列表，避免调用方绕过管理器直接修改运行态配置。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 当前配置列表的副本
     */
    public List<TestDataRangeConfig> getConfigs() {
        List<TestDataRangeConfig> copies = new ArrayList<>();
        for (TestDataRangeConfig config : configs) {
            copies.add(config.copy());
        }
        return copies;
    }

    /**
     * 更新当前进程内生效的测试数据范围配置列表。
     * 通过统一入口写入模型状态，便于解析器、规划器和 UI 之间传递数据。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @param configs 要展示或替换的测试数据范围配置列表
     */
    public void setConfigs(List<TestDataRangeConfig> configs) {
        this.configs = new ArrayList<>(configs);
        fireTableDataChanged();
    }

    /**
     * 校验当前表格中的配置行。
     * 该方法集中输出所有错误，方便对话框一次性提示用户需要修正的配置项。
     *
     * 异常情况：无主动抛出的受检异常；非法输入通常会通过空结果、告警或表格校验处理。
     *
     * @return 校验错误信息列表
     */
    public List<String> validateRows() {
        List<String> errors = new ArrayList<>();
        for (TestDataRangeConfig config : configs) {
            if (config.isStringType()) {
                if (config.getStringRegex() == null || config.getStringRegex().isBlank()) {
                    errors.add(config.getTypeName() + " 的字符串规则不能为空。");
                }
                continue;
            }
            if (config.isCandidateType()) {
                validateCandidateValues(config, errors);
                continue;
            }

            try {
                double min = Double.parseDouble(config.getMinValue());
                double max = Double.parseDouble(config.getMaxValue());
                if (min > max) {
                    errors.add(config.getTypeName() + " 的最小值不能大于最大值。");
                }
            } catch (NumberFormatException ex) {
                errors.add(config.getTypeName() + " 的最小值或最大值不是有效数字。");
            }

            if (config.isFloatingType()) {
                Integer scale = config.getDecimalScale();
                if (scale == null || scale < 0 || scale > 10) {
                    errors.add(config.getTypeName() + " 的小数位数必须在 0 到 10 之间。");
                }
            }
        }
        return errors;
    }

    /**
     * 校验候选值配置。
     * boolean 候选值只能是 true 或 false；char 候选值必须是单个字符或单字符字面量。
     */
    private void validateCandidateValues(TestDataRangeConfig config, List<String> errors) {
        String candidateValues = config.getCandidateValues();
        if (candidateValues == null || candidateValues.isBlank()) {
            errors.add(config.getTypeName() + " 的候选值不能为空。");
            return;
        }

        String[] candidates = candidateValues.split(",");
        boolean hasCandidate = false;
        for (String rawCandidate : candidates) {
            String candidate = rawCandidate.trim();
            if (candidate.isBlank()) {
                continue;
            }
            hasCandidate = true;
            if (config.isBooleanType()
                    && !"true".equalsIgnoreCase(candidate)
                    && !"false".equalsIgnoreCase(candidate)) {
                errors.add(config.getTypeName() + " 的候选值只能填写 true 或 false。");
                return;
            }
            if (config.isCharacterType() && normalizeCharCandidate(candidate).length() != 1) {
                errors.add(config.getTypeName() + " 的候选值必须是单个字符，多个字符请用英文逗号分隔。");
                return;
            }
        }
        if (!hasCandidate) {
            errors.add(config.getTypeName() + " 的候选值不能为空。");
        }
    }

    /**
     * 去除 char 候选值外层单引号，便于校验用户输入。
     */
    private String normalizeCharCandidate(String candidate) {
        if (candidate.length() >= 2 && candidate.startsWith("'") && candidate.endsWith("'")) {
            return candidate.substring(1, candidate.length() - 1);
        }
        return candidate;
    }

    /**
     * 把空配置值转换为短横线展示。
     * 短横线能区分“该类型不需要该配置”和表格未渲染。
     */
    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
