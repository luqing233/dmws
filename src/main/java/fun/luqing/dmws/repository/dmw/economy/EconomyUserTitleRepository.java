package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyUserTitle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyUserTitleRepository extends JpaRepository<EconomyUserTitle, Long> {

    List<EconomyUserTitle> findByUserIdOrderByAcquiredAtAsc(Long userId);

    Optional<EconomyUserTitle> findByUserIdAndTitleCode(Long userId, String titleCode);

    boolean existsByUserIdAndTitleCode(Long userId, String titleCode);
}
