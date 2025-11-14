package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.notice.PokeNoticeEvent;
import com.mikuac.shiro.enums.MsgTypeEnum;
import fun.luqing.dmws.service.TomatoBookChartService;
import fun.luqing.dmws.service.ai.impl.DouBaoAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
