package eval.cases.collection005;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryLookupTest {
    @Test void findsStock() { assertTrue(new InventoryLookup().available(Map.of("A", 3), "A", 2)); }
}
