package fun.luqing.dmws.repository.dmw.economy;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EconomyFishingAttemptRepository extends JpaRepository<EconomyFishingAttempt, String> {
    java.util.Optional<EconomyFishingAttempt> findFirstByGroupIdAndUserIdNotAndOccurredAtBetweenOrderByOccurredAtDescOperationIdDesc(
            long groupId, long userId, java.time.LocalDateTime since, java.time.LocalDateTime until);
}
