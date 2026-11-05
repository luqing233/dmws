package fun.luqing.dmws.service.dmw.economy;

import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

/** Injectable randomness keeps every gameplay branch reproducible in tests. */
@Component
public class EconomyRobRandom {
    public boolean chance(double probability) { return ThreadLocalRandom.current().nextDouble() < probability; }
    public double fraction() { return ThreadLocalRandom.current().nextDouble(); }
    public int injury() { return ThreadLocalRandom.current().nextInt(10, 101); }
}
