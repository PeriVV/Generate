package eval.cases.classexisting002;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CouponServiceTest {
    @Test void keepsManualNoCouponBehavior() {
        assertEquals(100, new CouponService().apply(100, "NONE"));
    }
}
