package MyUtils;

public class VariableInfo {
    private String name;
    private String type;
    private int startPosition;
    private int endPosition;

    public VariableInfo(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public void setStartPosition(int startPosition) {
        this.startPosition = startPosition;
    }

    public void setEndPosition(int endPosition) {
        this.endPosition = endPosition;
    }

    @Override
    public String toString() {
        return name + " : " + type + " [Start Position: " + startPosition + ", End Position: " + endPosition + "]";
    }
}
