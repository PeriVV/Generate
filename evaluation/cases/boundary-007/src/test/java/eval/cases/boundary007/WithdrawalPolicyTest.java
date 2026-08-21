package eval.cases.boundary007;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WithdrawalPolicyTest {
    @Test void keepsExistingBaseline() {
        assertEquals(70, new WithdrawalPolicy().remaining(100, 30));
    }
}
