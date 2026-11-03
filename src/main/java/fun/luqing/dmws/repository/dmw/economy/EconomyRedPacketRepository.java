package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyRedPacket;
import fun.luqing.dmws.enums.EconomyRedPacketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EconomyRedPacketRepository extends JpaRepository<EconomyRedPacket, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select packet from EconomyRedPacket packet where packet.id = :id")
    Optional<EconomyRedPacket> findForUpdate(@Param("id") Long id);

    List<EconomyRedPacket> findAllByGroupIdAndStatusOrderByIdDesc(
            Long groupId,
            EconomyRedPacketStatus status
    );

    List<EconomyRedPacket> findAllByStatusAndExpiresAtBefore(
            EconomyRedPacketStatus status,
            LocalDateTime expiresAt
    );
}
