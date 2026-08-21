package eval.cases.mock002;

public class AuditService {
    private final AuditSink sink;
    public AuditService(AuditSink sink) { this.sink = sink; }
    public void record(String event) { sink.append(event.trim()); }
}

interface AuditSink { void append(String event); }
