package com.casc.unittestagent.model;

/**
 * 标准化后的 Java 编译诊断。
 */
public class CompileDiagnostic {
    private String severity = "ERROR";
    private String code = "";
    private String message = "";
    private int line = -1;
    private int column = -1;
    private String snippet = "";
    private boolean repairable = true;
    /** TEST_SOURCE / TARGET_SOURCE / ENVIRONMENT / UNKNOWN. */
    private String sourceOrigin = "TEST_SOURCE";
    private String sourcePath = "";

    public CompileDiagnostic() {
    }

    public CompileDiagnostic(String severity, String code, String message, int line, int column, String snippet, boolean repairable) {
        this(severity, code, message, line, column, snippet, repairable, "TEST_SOURCE", "");
    }

    public CompileDiagnostic(String severity,
                             String code,
                             String message,
                             int line,
                             int column,
                             String snippet,
                             boolean repairable,
                             String sourceOrigin,
                             String sourcePath) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.line = line;
        this.column = column;
        this.snippet = snippet;
        this.repairable = repairable;
        this.sourceOrigin = sourceOrigin == null || sourceOrigin.isBlank() ? "UNKNOWN" : sourceOrigin;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public boolean isRepairable() {
        return repairable;
    }

    public void setRepairable(boolean repairable) {
        this.repairable = repairable;
    }

    public String getSourceOrigin() {
        return sourceOrigin;
    }

    public void setSourceOrigin(String sourceOrigin) {
        this.sourceOrigin = sourceOrigin == null || sourceOrigin.isBlank() ? "UNKNOWN" : sourceOrigin;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath == null ? "" : sourcePath;
    }

    @Override
    public String toString() {
        return code + (line > 0 ? "@" + line + ":" + column : "") + " " + message;
    }
}
