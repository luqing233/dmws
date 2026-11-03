package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyTitleDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyTitleDefinitionRepository extends JpaRepository<EconomyTitleDefinition, String> {

    List<EconomyTitleDefinition> findAllByEnabledTrueOrderByPriceAsc();

    Optional<EconomyTitleDefinition> findFirstByEnabledTrueAndName(String name);
}
