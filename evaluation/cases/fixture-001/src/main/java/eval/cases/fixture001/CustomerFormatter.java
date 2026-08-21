package eval.cases.fixture001;

public class CustomerFormatter {
    public String label(Customer customer) {
        if (customer == null) return "anonymous";
        return customer.name() + "#" + customer.id();
    }
}

record Customer(long id, String name) {}
