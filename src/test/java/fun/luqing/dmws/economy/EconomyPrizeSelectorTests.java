package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import fun.luqing.dmws.service.dmw.economy.EconomyPrizeSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyPrizeSelectorTests {

    @Test
    void selectsTheOnlyPositiveWeightPrize() {
        EconomyRafflePrize disabled = prize("disabled", 0);
        EconomyRafflePrize active = prize("active", 10);

        assertEquals("active", EconomyPrizeSelector.select(List.of(disabled, active)).getCode());
    }

    @Test
    void rejectsAnEmptyOrZeroWeightPool() {
        assertThrows(EconomyException.class, () -> EconomyPrizeSelector.select(List.of()));
        assertThrows(
                EconomyException.class,
                () -> EconomyPrizeSelector.select(List.of(prize("zero", 0)))
        );
    }

    private EconomyRafflePrize prize(String code, int weight) {
        EconomyRafflePrize prize = new EconomyRafflePrize();
        prize.setCode(code);
        prize.setWeight(weight);
        return prize;
    }
}
