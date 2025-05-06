package MyUtils;

public class IndexUsageInfo {
    private String indexValue;
    private String arrayName;
    private int position;

    public IndexUsageInfo(String indexValue, String arrayName, int position) {
        this.indexValue = indexValue;
        this.arrayName = arrayName;
        this.position = position;
    }

    public String getIndexValue() {
        return indexValue;
    }

    public String getArrayName() {
        return arrayName;
    }

    public int getPosition() {
        return position;
    }

    @Override
    public String toString() {
        return indexValue + " is used as an index for the array " + arrayName + " at position " + position;
    }
}
