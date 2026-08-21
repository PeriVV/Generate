package eval.cases.mock005;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuotaServiceTest {
    @Test void rejectsZeroBeforeUsingStore() {
        assertThrows(IllegalArgumentException.class, () -> new QuotaService(null).reserve("a", 0));
    }
}
