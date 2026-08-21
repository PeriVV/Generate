package eval.cases.classfixture001;

public class ProfileService {
    public String display(Profile profile) {
        if (profile == null || profile.name() == null) return "anonymous";
        return profile.name().trim() + "@" + region(profile.address());
    }
    public boolean complete(Profile profile) {
        return profile != null && profile.name() != null && !profile.name().isBlank()
                && profile.address() != null && profile.address().country() != null;
    }
    protected String region(Address address) {
        if (address == null || address.country() == null) return "unknown";
        return switch (address.country()) {
            case "CN", "JP", "SG" -> "APAC";
            case "US", "CA" -> "AMER";
            default -> "OTHER";
        };
    }
}

record Profile(String name, Address address) {}
record Address(String country, String city) {}
