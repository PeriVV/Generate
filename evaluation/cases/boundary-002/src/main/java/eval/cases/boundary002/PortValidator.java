package eval.cases.boundary002;

public class PortValidator {
    public int requireValid(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        return port;
    }
}
