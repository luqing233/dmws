package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.fish.Fish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyFishRepository extends JpaRepository<Fish, Integer> {

    boolean existsByName(String name);
    Optional<Fish> findBySeedName(String seedName);
    Optional<Fish> findFirstByNameOrderByIdAsc(String name);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Integer id);

    List<Fish> findAllByOrderByLevelAscPriceDesc();
    List<Fish> findByEnabledTrueOrderByLevelAscPriceDesc();
    List<Fish> findByEnabledTrueAndLevelLessThanEqualOrderByLevelAscPriceDesc(int level);
}
