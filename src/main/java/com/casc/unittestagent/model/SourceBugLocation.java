package com.casc.unittestagent.model;

/** 大模型只读归因给出的一个疑似生产代码位置。 */
public class SourceBugLocation {
    private String file = "";
    private int line = -1;
    private String symbol = "";
    private String reason = "";

    public String getFile() { return file; }
    public void setFile(String value) { file = value == null ? "" : value; }
    public int getLine() { return line; }
    public void setLine(int value) { line = value > 0 ? value : -1; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String value) { symbol = value == null ? "" : value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value == null ? "" : value; }
}
