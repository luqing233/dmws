package fun.luqing.dmws.service.dmw.economy;

import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EconomyFishingRandom {
    public long weight(long bound) { return ThreadLocalRandom.current().nextLong(bound); }
    public double fraction() { return ThreadLocalRandom.current().nextDouble(); }
    public boolean chance(double probability) { return ThreadLocalRandom.current().nextDouble() < probability; }
}
