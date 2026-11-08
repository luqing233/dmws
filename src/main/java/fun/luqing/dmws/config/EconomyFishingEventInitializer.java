package fun.luqing.dmws.config;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="dmws.economy", name="enabled", havingValue="true", matchIfMissing=true)
public class EconomyFishingEventInitializer implements ApplicationRunner {
    private final EconomyFishingEventRepository repository;
    @Transactional(transactionManager="dmwTransactionManager")
    public void run(ApplicationArguments args) throws Exception {
        try(var in=new ClassPathResource("economy/fishing-events.json").getInputStream()) {
            var entries=new JSONArray(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            for(int i=0;i<entries.length();i++) {
                var row=entries.getJSONObject(i);
                if(repository.existsById(row.getString("code"))) continue;
                var e=new EconomyFishingEvent();e.setCode(row.getString("code"));e.setName(row.getString("name"));
                e.setDescription(row.getString("description"));e.setKind(row.getString("kind"));
                e.setTickets(row.getInt("tickets"));e.setPenalty(new BigDecimal(row.getInt("penalty")));
                if(row.has("reward_min")) e.setRewardMin(BigDecimal.valueOf(row.getInt("reward_min")));
                if(row.has("reward_max")) e.setRewardMax(BigDecimal.valueOf(row.getInt("reward_max")));
                repository.save(e);
            }
        }
    }
}
