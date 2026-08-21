package eval.cases.mock003;

public class ExchangeService {
    private final RateClient client;
    public ExchangeService(RateClient client) { this.client = client; }
    public double convert(int cents, String currency) {
        return cents * client.rate(currency) / 100.0d;
    }
}

interface RateClient { double rate(String currency); }
