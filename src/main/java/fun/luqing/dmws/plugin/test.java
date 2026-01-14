package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.service.tomatoservice.TomatoBookChartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class test {


    @AnyMessageHandler
    @Async("taskExecutor")
    public void test1(Bot bot, AnyMessageEvent event) throws Exception {

    }

}
