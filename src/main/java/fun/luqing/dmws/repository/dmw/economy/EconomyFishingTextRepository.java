package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingTextTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EconomyFishingTextRepository extends JpaRepository<EconomyFishingTextTemplate, String> {
    java.util.List<EconomyFishingTextTemplate> findByOwnerCodeOrderByCodeAsc(String ownerCode);
    List<EconomyFishingTextTemplate> findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc(String ownerCode, String scene);
    boolean existsByOwnerCodeAndScene(String ownerCode, String scene);
}
