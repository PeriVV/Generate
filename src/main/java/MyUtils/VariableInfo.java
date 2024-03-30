package MyUtils;

public class VariableInfo {
    private String name;
    private String type;
    private boolean selected;
    private boolean input;
    private int startPosition;
    private int endPosition;
    private String callContext;

    public VariableInfo(String name, String type) {
        this.name = name;
        this.type = type;
        this.selected = true;
        this.input = true;
        this.callContext = callContext;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isSelected() {
        return selected;
    }

    public boolean isInput() {
        return input;
    }

    public int getStartPosition() {
        return startPosition;
    }

    public int getEndPosition() {
        return endPosition;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setInput(boolean input) {
        this.input = input;
    }

    public void setStartPosition(int startPosition) {
        this.startPosition = startPosition;
    }

    public void setEndPosition(int endPosition) {
        this.endPosition = endPosition;
    }

    public String getCallContext() {
        return callContext;
    }

    public void setCallContext(String callContext) {
        this.callContext = callContext;
    }

    @Override
    public String toString() {
        return name + " : " + type + " [Start Position: " + startPosition + ", End Position: " + endPosition + "]";
    }
}
