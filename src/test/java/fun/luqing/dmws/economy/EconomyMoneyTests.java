package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.plugin.economy.EconomyCommandSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyMoneyTests {

    @Test
    void parsesChineseSuffixesAndRoundsHalfUp() {
        assertEquals(new BigDecimal("15000.00"), EconomyMoney.parse("1.5万"));
        assertEquals(new BigDecimal("250000000.00"), EconomyMoney.parse("2.5亿"));
        assertEquals(new BigDecimal("1200.00"), EconomyMoney.parse("1.2k"));
        assertEquals(new BigDecimal("10.01"), EconomyMoney.normalize(new BigDecimal("10.005")));
        assertEquals(new BigDecimal("1200.50"), EconomyMoney.parse("￥1,200.50元"));
        assertEquals(new BigDecimal("100.00"), EconomyMoney.parse("１００金币"));
        assertEquals(new BigDecimal("15000.00"), EconomyMoney.parse("1.5万元"));
    }

    @Test
    void rejectsZeroNegativeAndInvalidAmounts() {
        assertThrows(EconomyException.class, () -> EconomyMoney.parse("0"));
        assertThrows(EconomyException.class, () -> EconomyMoney.parse("-1"));
        assertThrows(EconomyException.class, () -> EconomyMoney.parse("abc"));
    }

    @Test
    void allAmountMarkersAcceptHalfAndFullWidthExclamationAndWords() {
        assertTrue(EconomyCommandSupport.requestsAll(""));
        assertTrue(EconomyCommandSupport.requestsAll("!"));
        assertTrue(EconomyCommandSupport.requestsAll("！"));
        assertTrue(EconomyCommandSupport.requestsAll(" ！ "));
        assertTrue(EconomyCommandSupport.requestsAll("全部"));
        assertFalse(EconomyCommandSupport.requestsAll("！500"));
    }
}
