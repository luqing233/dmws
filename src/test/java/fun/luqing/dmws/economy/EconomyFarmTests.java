package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyFarmPlot;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.EconomyFarmPlotRepository;
import fun.luqing.dmws.service.dmw.economy.EconomyAdminService;
import fun.luqing.dmws.service.dmw.economy.EconomyFarmService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFarmTests {

    @Autowired
    private EconomyAdminService adminService;

    @Autowired
    private EconomyLedgerService ledgerService;

    @Autowired
    private EconomyFarmService farmService;

    @Autowired
    private EconomyFarmPlotRepository plotRepository;

    @Test
    void plantWaterAndHarvestUseTheExpectedLedgerEntries() {
        long userId = 95001L;
        adminService.adjust(userId, userId, "farmer", new BigDecimal("100"), true, "farm-seed");

        EconomyFarmService.PlotView planted =
                farmService.plant(userId, "farmer", "wheat", 1, "farm-plant");
        assertEquals("小麦", planted.cropName());
        assertEquals(
                new BigDecimal("80.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertThrows(
                EconomyException.class,
                () -> farmService.plant(userId, "farmer", "wheat", 1, "farm-plant-again")
        );

        EconomyFarmService.PlotView watered = farmService.water(userId, "farmer", 1);
        assertTrue(watered.watered());
        assertThrows(EconomyException.class, () -> farmService.water(userId, "farmer", 1));

        EconomyFarmPlot plot = plotRepository.findByUserIdAndPlotIndex(userId, 1).orElseThrow();
        plot.setReadyAt(LocalDateTime.now().minusSeconds(1));
        plotRepository.saveAndFlush(plot);

        EconomyFarmService.HarvestView harvest =
                farmService.harvest(userId, "farmer", 1, "farm-harvest");
        assertTrue(harvest.yield() >= 2);
        assertTrue(harvest.reward().compareTo(new BigDecimal("16.00")) >= 0);
        assertTrue(ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
                .compareTo(new BigDecimal("80.00")) > 0);
    }
}
