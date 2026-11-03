package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyUserItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EconomyUserItemRepository extends JpaRepository<EconomyUserItem, Long> {

    List<EconomyUserItem> findByUserIdAndQuantityGreaterThanOrderByItemCodeAsc(Long userId, int quantity);

    Optional<EconomyUserItem> findByUserIdAndItemCode(Long userId, String itemCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
              from EconomyUserItem item
             where item.userId = :userId
               and item.itemCode = :itemCode
            """)
    Optional<EconomyUserItem> findForUpdate(
            @Param("userId") Long userId,
            @Param("itemCode") String itemCode
    );
}
