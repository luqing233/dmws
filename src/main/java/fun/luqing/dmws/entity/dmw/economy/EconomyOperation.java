package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_operation")
public class EconomyOperation {

    @Id
    @Column(name = "operation_id", length = 80)
    private String operationId;

    @Column(nullable = false, length = 64)
    private String operationType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static EconomyOperation create(
            String operationId,
            String operationType,
            Long actorId,
            String description
    ) {
        EconomyOperation operation = new EconomyOperation();
        operation.operationId = operationId;
        operation.operationType = operationType;
        operation.actorId = actorId;
        operation.description = description;
        operation.createdAt = LocalDateTime.now();
        return operation;
    }
}
