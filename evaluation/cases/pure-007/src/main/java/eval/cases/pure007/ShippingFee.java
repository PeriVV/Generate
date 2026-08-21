package eval.cases.pure007;

public class ShippingFee {
    public int calculate(int weight, int distance, boolean express, boolean remote) {
        int fee = weight <= 5 ? 8 : 15;
        if (distance > 100) fee += 10;
        if (express) fee += 20;
        if (remote) fee += 12;
        if (weight > 30) fee += 25;
        return fee;
    }
}
