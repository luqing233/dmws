package fun.luqing.dmws.service.dmw.economy;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConsumeResult {
    private boolean success;
    private double walletBalance;
    private double bankBalance;
}
