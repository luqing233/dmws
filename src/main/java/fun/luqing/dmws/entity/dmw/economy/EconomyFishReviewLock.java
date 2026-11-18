package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

/** Serializes catalog publication and proposal deduplication, without locking game accounts. */
@Data @Entity @Table(name = "economy_fish_review_lock")
public class EconomyFishReviewLock {
    @Id private Integer id;
}
