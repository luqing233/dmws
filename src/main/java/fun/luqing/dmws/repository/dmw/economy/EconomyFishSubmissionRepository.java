package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface EconomyFishSubmissionRepository extends JpaRepository<EconomyFishSubmission, Long> {
    Optional<EconomyFishSubmission> findByRequestId(String requestId);
    Optional<EconomyFishSubmission> findByDecisionRequestId(String requestId);
    boolean existsByNameIgnoreCaseAndStatus(String name, Status status);
    java.util.List<EconomyFishSubmission> findBySubmissionTypeAndStatus(String type, Status status);
    long countByStatus(Status status);

    @Query("select s from EconomyFishSubmission s where (:status is null or s.status=:status) "
            + "and (lower(s.name) like lower(concat('%',:query,'%')) or lower(s.author) like lower(concat('%',:query,'%')))")
    Page<EconomyFishSubmission> search(@Param("status") Status status, @Param("query") String query, Pageable pageable);
}
