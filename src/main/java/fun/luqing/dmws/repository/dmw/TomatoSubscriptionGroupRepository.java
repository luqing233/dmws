package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TomatoSubscriptionGroupRepository extends JpaRepository<TomatoSubscriptionGroup, Long> {
    Optional<TomatoSubscriptionGroup> findByGroupIdAndBookId(long groupId, String bookId);
    List<TomatoSubscriptionGroup> findAllByGroupId(long groupId);
    List<TomatoSubscriptionGroup> findAllByBookId(String bookId);

    List<TomatoSubscriptionGroup> findByGroupId(long groupId);

    List<TomatoSubscriptionGroup> findByBookId(String bookId);
}
