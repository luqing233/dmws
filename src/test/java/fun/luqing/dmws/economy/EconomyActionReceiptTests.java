package fun.luqing.dmws.economy;

import fun.luqing.dmws.service.dmw.economy.EconomyConditionService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class EconomyActionReceiptTests {
    private EconomyConditionService.Status status() {
        return new EconomyConditionService.Status(1L,0,0,null,null,BigDecimal.ZERO,BigDecimal.ZERO,
                new BigDecimal("655.00"),BigDecimal.ZERO,LocalDateTime.of(2026,11,4,21,54));
    }

    @Test
    void dischargeOnlyReportsOutcomeAndFeeWhenThereIsNoDebt() {
        var text = new EconomyConditionService.DischargeReceipt(status(),true,BigDecimal.ZERO).message();
        assertEquals("出院成功\n已缴纳医药费：655.00 金币",text);
        assertFalse(text.contains("犯罪值")); assertFalse(text.contains("未住院"));
        assertFalse(text.contains("欠款")); assertFalse(text.contains("上次出院"));
    }

    @Test
    void existingMedicalDebtIsShownAndRepeatedReceiptDoesNotClaimANewCharge() {
        var text = new EconomyConditionService.DischargeReceipt(status(),false,new BigDecimal("100")).message();
        assertTrue(text.startsWith("你已出院")); assertTrue(text.contains("医药费已结算：655.00"));
        assertTrue(text.contains("总欠款：100.00")); assertFalse(text.contains("出院成功"));
    }
}
