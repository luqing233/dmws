package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

public interface EconomyOperationRepository extends JpaRepository<EconomyOperation, String> {
    @Modifying
    @Query(value = "INSERT INTO economy_operation (operation_id, operation_type, actor_id, description, created_at) "
            + "VALUES (:id, :type, :actor, :description, :created)", nativeQuery = true)
    void reserve(@Param("id") String id, @Param("type") String type, @Param("actor") Long actor,
                 @Param("description") String description, @Param("created") LocalDateTime created);
}
