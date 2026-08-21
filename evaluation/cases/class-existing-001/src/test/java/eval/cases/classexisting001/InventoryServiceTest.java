package eval.cases.classexisting001;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryServiceTest {
    @Test void keepsManualContract() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryService(null).reserve("A", 0));
    }
}
