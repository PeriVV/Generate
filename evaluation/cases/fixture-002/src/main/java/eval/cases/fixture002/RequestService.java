package eval.cases.fixture002;

public class RequestService {
    public String describe(Request request) {
        if (request == null || request.key() == null) throw new IllegalArgumentException("request");
        return request.key() + ":" + request.priority();
    }
}

final class Request {
    private final String key;
    private final int priority;
    private Request(Builder builder) { key = builder.key; priority = builder.priority; }
    String key() { return key; }
    int priority() { return priority; }
    static Builder builder() { return new Builder(); }
    static final class Builder {
        private String key; private int priority;
        Builder key(String value) { key = value; return this; }
        Builder priority(int value) { priority = value; return this; }
        Request build() { return new Request(this); }
    }
}
