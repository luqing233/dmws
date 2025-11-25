package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserStatusRepository extends JpaRepository<UserStatus, Long> {
    // JpaRepository 已经提供了常用的增删改查
}
