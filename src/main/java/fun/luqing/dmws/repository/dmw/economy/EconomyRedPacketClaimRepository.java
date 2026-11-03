package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyRedPacketClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EconomyRedPacketClaimRepository extends JpaRepository<EconomyRedPacketClaim, Long> {

    Optional<EconomyRedPacketClaim> findByPacketIdAndUserId(Long packetId, Long userId);

    List<EconomyRedPacketClaim> findByPacketIdOrderByIdAsc(Long packetId);
}
