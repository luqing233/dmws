package fun.luqing.dmws.common.utils.wait;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeoutException;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageWaitManager {

    private final WaitNextMessage waitManager; // 构造函数注入

    /** 全局接收所有消息，分发给等待请求 */
    @AnyMessageHandler
    @Async("taskExecutor")
    public void onMessage(AnyMessageEvent event) {
        waitManager.handleEvent(event);
    }

    /** 测试指令示例 */
    @AnyMessageHandler
    @Async("taskExecutor")
    @MessageHandlerFilter(startWith = "next")
    public void test1(Bot bot, AnyMessageEvent event) {
        try {
            bot.sendMsg(event, "请输入下一条消息，我会返回它的内容", false);

            AnyMessageEvent next = waitManager.of(event).next(30);

            String msg = next.getMessage();

            bot.sendMsg(event, "你刚才输入的是：" + msg, false);

        } catch (TimeoutException e) {
            bot.sendMsg(event, "你太久没有回复，流程已取消", false);
        } catch (Exception e) {
            bot.sendMsg(event, "流程异常：" + e.getMessage(), false);
        }
    }

}
