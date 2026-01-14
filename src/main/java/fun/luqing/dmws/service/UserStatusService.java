package fun.luqing.dmws.service;

import fun.luqing.dmws.entity.dmw.user.UserStatus;
import fun.luqing.dmws.repository.dmw.UserStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserStatusService {


    private final UserStatusRepository repository;

    /**
     * 查询所有用户状态记录。
     *
     * @return 返回包含所有用户状态的列表。
     */

    public List<UserStatus> findAll() {
        return repository.findAll();
    }

    /**
     * 根据用户ID查询用户状态信息。
     *
     * @param userId 用户的唯一标识符
     * @return 返回一个Optional对象，包含与给定用户ID关联的UserStatus实例，如果未找到则返回空的Optional
     */

    public Optional<UserStatus> findById(Long userId) {
        return repository.findById(userId);
    }

    /**
     * 保存或更新用户状态信息。
     * 如果传入的UserStatus对象已经存在，则执行更新操作；如果不存在，则插入新记录。
     *
     * @param userStatus 待保存或更新的用户状态对象
     * @return 保存或更新后的用户状态对象
     */

    public UserStatus save(UserStatus userStatus) {
        return repository.save(userStatus);
    }

    /**
     * 更新指定用户的地点信息。
     *
     * @param userId 用户的唯一标识符
     * @param place  用户的新地点，必须是PlaceEnum枚举中的一个值
     */

/*    public void updatePlace(Long userId, PlaceEnum place) {
        repository.findById(userId).ifPresent(us -> {
            us.setPlace(place);
            repository.save(us);
        });
    }*/

    /**
     * 更新指定用户的恢复时间。
     * 如果用户存在，则更新其恢复时间；如果用户不存在，则不执行任何操作。
     *
     * @param userId 用户的唯一标识符
     * @param recoveryTime 用户的新恢复时间
     */

    public void updateRecoveryTime(Long userId, Integer recoveryTime) {
        repository.findById(userId).ifPresent(us -> {
            us.setRecoveryTime(recoveryTime);
            repository.save(us);
        });
    }

    /**
     * 更新指定用户的开始时间。
     *
     * @param userId 用户ID
     * @param startTime 新的开始时间
     */

    public void updateStartTime(Long userId, LocalDateTime startTime) {
        repository.findById(userId).ifPresent(us -> {
            us.setStartTime(startTime);
            repository.save(us);
        });
    }

    /**
     * 删除指定用户ID的记录。
     *
     * @param userId 用户ID
     */

    public void delete(Long userId) {
        repository.deleteById(userId);
    }

    /**
     * 判断指定用户ID的用户是否存在。
     *
     * @param userId 用户的唯一标识符
     * @return 如果用户存在则返回true，否则返回false
     */

    public boolean exists(Long userId) {
        return repository.existsById(userId);
    }

}
