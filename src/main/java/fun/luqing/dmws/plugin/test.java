package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.notice.PokeNoticeEvent;
import com.mikuac.shiro.enums.MsgTypeEnum;
import fun.luqing.dmws.service.ai.impl.DouBaoAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class test {





    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "1122")
    public void test1(Bot bot, PokeNoticeEvent event) {
        JSONArray array = new JSONArray();

        JSONObject innerObj = new JSONObject();
        innerObj.put("role", "user");

        JSONArray contentArray = new JSONArray();

        JSONObject imageObj = new JSONObject();
        imageObj.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "");
        imageObj.put("image_url", imageUrl);

        JSONObject textObj = new JSONObject();
        textObj.put("type", "text");
        textObj.put("text", "图片主要讲了什么?");

        contentArray.put(imageObj);
        contentArray.put(textObj);

        innerObj.put("content", contentArray);

        array.put(innerObj);




    }



}
