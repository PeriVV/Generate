package eval.cases.mock004;

public class NotificationService {
    private final MessageGateway gateway;
    public NotificationService(MessageGateway gateway) { this.gateway = gateway; }
    public boolean notify(String address, String message) {
        if (address == null || address.isBlank()) return false;
        gateway.send(address, message);
        return true;
    }
}

interface MessageGateway { void send(String address, String message); }
