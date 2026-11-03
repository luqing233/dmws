package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFarmCrop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyFarmCropRepository extends JpaRepository<EconomyFarmCrop, String> {

    List<EconomyFarmCrop> findAllByEnabledTrueOrderBySeedPriceAsc();

    Optional<EconomyFarmCrop> findFirstByEnabledTrueAndName(String name);
}
