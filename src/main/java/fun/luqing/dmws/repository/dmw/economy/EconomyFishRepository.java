package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.fish.Fish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EconomyFishRepository extends JpaRepository<Fish, Integer> {

    boolean existsByName(String name);

    List<Fish> findAllByOrderByLevelAscPriceDesc();
}
