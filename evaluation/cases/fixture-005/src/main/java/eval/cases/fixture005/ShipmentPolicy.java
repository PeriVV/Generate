package eval.cases.fixture005;

public class ShipmentPolicy {
    public String route(Shipment shipment) {
        if (shipment == null || shipment.destination() == null) throw new IllegalArgumentException("shipment");
        Address address = shipment.destination();
        if (address.country() == null || address.country().isBlank()) return "INVALID";
        if (shipment.weight() <= 0) return "INVALID";
        if (shipment.weight() > 100) return "FREIGHT";
        if ("CN".equals(address.country()) && address.postalCode().startsWith("9")) return "REMOTE";
        if (shipment.fragile() && shipment.weight() > 20) return "SPECIAL";
        if (shipment.express()) return "AIR";
        return "GROUND";
    }
}

record Address(String country, String postalCode) {}
record Shipment(Address destination, int weight, boolean fragile, boolean express) {}
