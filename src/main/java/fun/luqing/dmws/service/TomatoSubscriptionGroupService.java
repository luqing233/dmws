package fun.luqing.dmws.service;

import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;
import fun.luqing.dmws.repository.dmw.TomatoBookListRepository;
import fun.luqing.dmws.repository.dmw.TomatoSubscriptionGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TomatoSubscriptionGroupService {


    private final TomatoSubscriptionGroupRepository repository;
    private final TomatoBookListRepository tomatoBookListRepository;

    // 查询所有订阅
    public List<TomatoSubscriptionGroup> findAll() {
        log.info("查询所有订阅记录");
        return repository.findAll();
    }

    // 根据 ID 查询
    public Optional<TomatoSubscriptionGroup> findById(Long id) {
        log.info("查询订阅记录, id={}", id);
        return repository.findById(id);
    }

    // 根据群号和书籍ID查询
    public Optional<TomatoSubscriptionGroup> findByGroupIdAndBookId(long groupId, String bookId) {
        log.info("查询订阅记录, groupId={}, bookId={}", groupId, bookId);
        return repository.findByGroupIdAndBookId(groupId, bookId);
    }

    // 新增订阅
    public TomatoSubscriptionGroup addSubscription(TomatoSubscriptionGroup subscription) {
        log.info("新增订阅, groupId={}, bookId={}", subscription.getGroupId(), subscription.getBookId());
        TomatoSubscriptionGroup saved = repository.save(subscription);
        log.info("新增订阅成功, id={}", saved.getId());
        return saved;
    }

    // 更新订阅状态（启用/禁用）
    public TomatoSubscriptionGroup updateEnable(Long id, boolean enable) {
        log.info("更新订阅启用状态, id={}, enable={}", id, enable);
        return repository.findById(id).map(sub -> {
            sub.setEnable(enable);
            TomatoSubscriptionGroup updated = repository.save(sub);
            log.info("更新成功, id={}", id);
            return updated;
        }).orElseThrow(() -> {
            log.error("更新失败，订阅不存在, id={}", id);
            return new RuntimeException("订阅不存在：" + id);
        });
    }

    // 删除订阅
    public void deleteById(Long id) {
        log.info("删除订阅记录, id={}", id);
        repository.findById(id).ifPresentOrElse(
                repository::delete,
                () -> log.warn("删除失败，订阅不存在, id={}", id)
        );
    }

    // 查询指定群的订阅列表
    public List<TomatoSubscriptionGroup> findByGroupId(long groupId) {
        log.info("查询指定群的订阅列表, groupId={}", groupId);
        return repository.findByGroupId(groupId);
    }

    // 查询指定书籍的订阅列表
    public List<TomatoSubscriptionGroup> findByBookId(String bookId) {
        log.info("查询指定书籍的订阅列表, bookId={}", bookId);
        return repository.findByBookId(bookId);
    }

    /**
     * 获取某个群的订阅列表
     * @param groupId 群号
     * @return 订阅列表消息内容，如果为空返回 null
     */
    public List<String> getGroupSubscriptionList(long groupId) {
        List<TomatoSubscriptionGroup> subscriptions = repository.findAllByGroupId(groupId);
        if (subscriptions.isEmpty()) {
            return null;
        }
        List<String> messageList = new ArrayList<>();
        messageList.add("本群订阅列表：");

        for (TomatoSubscriptionGroup sub : subscriptions) {
            String bookName = tomatoBookListRepository.findByBookId(sub.getBookId())
                    .map(TomatoBookList::getBookName)
                    .orElse("[未知书名]");

            messageList.add("ID: " + sub.getId());
            messageList.add("《" + bookName + "》");
            messageList.add("bookId: " + sub.getBookId() + (sub.isAtAll() ? "（@全体）" : ""));
        }
        return messageList;
    }






}
