package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.service.TomatoBookChartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class test {


    private final TomatoBookChartService tomatoBookChartService;


    @AnyMessageHandler
    @Async("taskExecutor")
    @MessageHandlerFilter(cmd = "1122")
    public void test1(Bot bot, AnyMessageEvent event) throws Exception {
        //tomatoBookChartService.generateBook30DaysChart("7549021282038189081","薇芮缇丝的书库");





    }



}
