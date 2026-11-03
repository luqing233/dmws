package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBank;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EconomyPrivateBankRepository extends JpaRepository<EconomyPrivateBank, Long> {

    Optional<EconomyPrivateBank> findByCodeIgnoreCase(String code);

    Optional<EconomyPrivateBank> findFirstByNameIgnoreCase(String name);

    Optional<EconomyPrivateBank> findByOwnerId(Long ownerId);

    List<EconomyPrivateBank> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bank from EconomyPrivateBank bank where bank.id = :id")
    Optional<EconomyPrivateBank> findForUpdate(@Param("id") Long id);
}
