package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EconomyUserProfileRepository extends JpaRepository<EconomyUserProfile, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from EconomyUserProfile profile where profile.userId = :userId")
    Optional<EconomyUserProfile> findForUpdate(@Param("userId") Long userId);
}
