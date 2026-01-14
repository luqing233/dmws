package fun.luqing.dmws.common.utils.wait;

import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class WaitNextMessage {

    private final List<WaitRequest> waitList = new CopyOnWriteArrayList<>();

    public static class WaitRequest {
        private final Optional<Long> groupId;
        private final Long userId;
        private final CompletableFuture<AnyMessageEvent> future;

        public WaitRequest(Optional<Long> groupId, Long userId) {
            this.groupId = groupId;
            this.userId = userId;
            this.future = new CompletableFuture<>();
        }

        public void handle(AnyMessageEvent event) {
            Long eUserId = event.getUserId();
            Long eGroupId = event.getGroupId();

            boolean userOk  = userId.equals(eUserId);
            boolean groupOk = groupId.isEmpty() || (eGroupId != null && groupId.get().equals(eGroupId));

            if (userOk && groupOk) {
                future.complete(event);
            }
        }

        public AnyMessageEvent getNext(long timeoutSeconds) throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 注册一个新的等待请求，用于接收来自用户的下一条消息，可选地在特定群组内。
     *
     * @param event 触发注册的AnyMessageEvent。它包含用户ID和可选的群组ID。
     * @return 一个表示等待下一条消息的WaitRequest对象。此对象可用于检索下一条消息或检查是否已收到。
     */
    public WaitRequest register(AnyMessageEvent event) {
        Long userId = event.getUserId();
        Optional<Long> groupId = Optional.ofNullable(event.getGroupId());
        WaitRequest req = new WaitRequest(groupId, userId);
        waitList.add(req);
        return req;
    }

    /**
     * 处理传入的消息事件，并根据条件完成等待请求。
     *
     * @param event 传入的任意消息事件，用于匹配和处理等待列表中的请求
     */
    public void handleEvent(AnyMessageEvent event) {
        for (WaitRequest req : waitList) {
            req.handle(event);
            if (req.future.isDone()) {
                waitList.remove(req);
            }
        }
    }

    public record Chain(WaitRequest request) {

        /**
         * 等待下一条消息（真正抛 TimeoutException）
         */
            public AnyMessageEvent next(long timeoutSeconds)
                    throws TimeoutException, ExecutionException, InterruptedException {

                return request.getNext(timeoutSeconds);
            }

            /**
             * await 与 next 等价，只是语义不同
             */
            public AnyMessageEvent await(long timeoutSeconds)
                    throws TimeoutException, ExecutionException, InterruptedException {

                return request.getNext(timeoutSeconds);
            }
        }


    /**
     * 根据提供的AnyMessageEvent创建一个新的Chain实例。
     *
     * @param event 用于注册并创建链的AnyMessageEvent
     * @return 一个包含已注册WaitRequest的新Chain实例
     */
    public Chain of(AnyMessageEvent event) {
        return new Chain(register(event));
    }
}
