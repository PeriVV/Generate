package MyUtils;

import javax.swing.table.AbstractTableModel;
import java.util.List;

public class VariableTableModel extends AbstractTableModel {
    private final List<VariableInfo> variableList;

    public VariableTableModel(List<VariableInfo> variableList) {
        this.variableList = variableList;
        // 初始化每个 VariableInfo 对象的 isSelected 属性为 true
        for (VariableInfo variable : variableList) {
            variable.setSelected(true);
        }
    }

    @Override
    public int getRowCount() {
        return variableList.size();
    }

    @Override
    public int getColumnCount() {
        return 3;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        VariableInfo variableInfo = variableList.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return variableInfo.isSelected();
            case 1:
                return variableInfo.getName();
            case 2:
                return variableInfo.getType();
            default:
                return null;
        }
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        if (columnIndex == 0) {
            return Boolean.class;
        } else {
            return String.class;
        }
    }

    @Override
    public String getColumnName(int column) {
        switch (column) {
            case 0:
                return "Selected";
            case 1:
                return "Name";
            case 2:
                return "Type";
            default:
                return null;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0;
    }

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (columnIndex == 0) {
            boolean isSelected = (boolean) aValue;
            VariableInfo variableInfo = variableList.get(rowIndex);
            variableInfo.setSelected(isSelected);
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}

