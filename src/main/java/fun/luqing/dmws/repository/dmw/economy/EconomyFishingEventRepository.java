package fun.luqing.dmws.repository.dmw.economy;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EconomyFishingEventRepository extends JpaRepository<EconomyFishingEvent, String> {
    java.util.List<EconomyFishingEvent> findByEnabledTrueOrderByCodeAsc();
}
