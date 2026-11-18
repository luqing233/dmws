package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.MsgId;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.old.SeTuImageService;
import fun.luqing.dmws.service.old.SeTuImageService.ImageResult;
import fun.luqing.dmws.service.old.SeTuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class SeTuPlugin {
    private final SeTuService seTuService;
    private final SeTuImageService imageService;
    private final DmwConfig config;

    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "涩图")
    @Async("taskExecutor")
    @CommandInfo(startWith = "涩图", desc = "按标签找图：涩图 tag=标签1 标签2 [num=1-5] [r18=0/1/2] [size=regular/original] [proxy=域名] [excludeai=true]")
    public void getSeTu(Bot bot, AnyMessageEvent event) {
        String text = event.getMessage() == null ? "" : event.getMessage().trim();
        if (!text.matches("^涩图(?:\\s.*)?$")) return;
        SeTuRequest request;
        try {
            request = SeTuRequest.parse(text);
        } catch (IllegalArgumentException exception) {
            reply(bot, event, exception.getMessage());
            return;
        }
        try {
            if (request.r18() != 0 && !isPrivileged(bot, event)) {
                reply(bot, event, "R18 模式仅限主人或本群管理员使用");
                return;
            }
            JSONArray data = seTuService.getLoliconImageByKeyword(request.tags(), null, request.r18(),
                    request.num(), request.sizes(), request.proxy(), request.excludeAI());
            if (!data.isEmpty() && data.getJSONObject(0).has("error")) {
                log.warn("涩图 API 请求失败: {}", data.getJSONObject(0).optString("error"));
                reply(bot, event, "图片服务暂时不可用，请稍后重试。");
                return;
            }
            if (data.isEmpty()) {
                log.info("涩图搜索无结果: message={}, tags={}, r18={}", event.getMessageId(), request.tags(), request.r18());
                String hint = request.tags().size() > 1
                        ? "空格分隔的标签需要同时命中，可减少标签，或用 | 表示任选其一。"
                        : "图库可能未收录这个名称，试试其他译名或日文名。";
                reply(bot, event, "没有找到符合这些标签的作品。\n" + hint);
                return;
            }
            reply(bot, event, "找到 " + Math.min(data.length(), 5) + " 张作品，正在加载图片…");
            List<ImageResult> images = imageService.download(data, request.sizes());
            long loaded = images.stream().filter(image -> image.path() != null).count();
            if (loaded == 0) {
                sendInformationForward(bot, event, request, images, "图片未能加载，以下保留作品信息与链接。");
                return;
            }
            List<String> nodes = new ArrayList<>();
            String summary = "涩图 · 作品集\n标签：" + String.join(" / ", request.tags())
                    + "\n共 " + images.size() + " 张 · 已加载 " + loaded + " 张";
            if (loaded < images.size()) summary += " · 未加载 " + (images.size() - loaded) + " 张";
            nodes.add(ShiroUtils.escape(summary));
            for (int i = 0; i < images.size(); i++) {
                ImageResult image = images.get(i);
                String caption = image.path() == null ? informationCaption(image, i, images.size()) : caption(image, i, images.size());
                String node = ShiroUtils.escape(caption);
                if (image.path() != null) {
                    String url = "http://" + config.getFileServerHost() + ":" + config.getFileServerPort()
                            + "/" + image.path().getFileName();
                    node += "\n" + MsgUtils.builder().img(url).build();
                }
                nodes.add(node);
            }
            Delivery result = sendForward(bot, event, nodes, "images");
            if (result == Delivery.SENT) return;
            sendInformationForward(bot, event, request, images, result == Delivery.UNKNOWN
                    ? "图片发送回执尚未确认，以下仅保留作品信息与链接。"
                    : "QQ 未能发送图片，以下保留作品信息与链接。");
        } catch (RuntimeException exception) {
            log.error("获取涩图失败", exception);
            reply(bot, event, "获取图片失败，请稍后重试。");
        }
    }

    private static String caption(ImageResult image, int index, int total) {
        String text = (index + 1) + " / " + total + " ·《" + bounded(image.title()) + "》\n作者：" + bounded(image.author());
        if (image.source() != null) text += "\n作品来源：" + image.source();
        return text;
    }

    private static String bounded(String text) {
        String value = text == null ? "未知" : text.replaceAll("[\\r\\n]+", " ").trim();
        int count = value.codePointCount(0, value.length());
        return count <= 120 ? value : value.substring(0, value.offsetByCodePoints(0, 120)) + "…";
    }

    private static String informationCaption(ImageResult image, int index, int total) {
        String text = caption(image, index, total);
        if (image.path() == null) text += "\n图片未加载：" + image.error();
        if (image.imageUrl() != null && !image.imageUrl().equals(image.source())) text += "\n图片链接：" + image.imageUrl();
        return text;
    }

    private void sendInformationForward(Bot bot, AnyMessageEvent event, SeTuRequest request, List<ImageResult> images, String heading) {
        List<String> nodes = new ArrayList<>();
        nodes.add(ShiroUtils.escape("涩图 · 作品信息\n标签：" + String.join(" / ", request.tags())
                + "\n共 " + images.size() + " 张作品\n" + heading));
        for (int i = 0; i < images.size(); i++) {
            ImageResult image = images.get(i);
            nodes.add(ShiroUtils.escape(informationCaption(image, i, images.size())));
        }
        Delivery result = sendForward(bot, event, nodes, "links");
        if (result == Delivery.UNKNOWN) reply(bot, event, "作品信息转发的回执尚未确认，请稍后查看。");
        else if (result == Delivery.REJECTED) reply(bot, event, "QQ 未能发送作品信息转发，请稍后重试。");
    }

    private Delivery sendForward(Bot bot, AnyMessageEvent event, List<String> nodes, String mode) {
        long started = System.nanoTime();
        try {
            long sender = bot.getSelfId() > 0 ? bot.getSelfId() : config.getBotId();
            String name = config.getBotName() == null || config.getBotName().isBlank() ? "DMW" : config.getBotName();
            var forward = ShiroUtils.generateForwardMsg(sender, name, nodes);
            ActionData<MsgId> response;
            if ("group".equals(event.getMessageType())) response = bot.sendGroupForwardMsg(event.getGroupId(), forward);
            else if ("private".equals(event.getMessageType())) response = bot.sendPrivateForwardMsg(event.getUserId(), forward);
            else throw new IllegalArgumentException("未知消息会话类型");
            Integer code = response == null ? null : response.getRetCode();
            Integer messageId = response == null || response.getData() == null ? null : response.getData().getMessageId();
            Delivery result = Integer.valueOf(0).equals(code) && messageId != null && messageId != 0 ? Delivery.SENT
                    : code != null && code > 0 ? Delivery.REJECTED : Delivery.UNKNOWN;
            log.info("涩图转发回执: sourceMessage={}, type={}, mode={}, nodes={}, state={}, retcode={}, messageId={}, elapsedMs={}",
                    event.getMessageId(), event.getMessageType(), mode, nodes.size(), result, code, messageId,
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException exception) {
            log.warn("涩图转发结果不明: sourceMessage={}, mode={}", event.getMessageId(), mode, exception);
            return Delivery.UNKNOWN;
        }
    }

    private enum Delivery { SENT, REJECTED, UNKNOWN }

    private boolean isPrivileged(Bot bot, AnyMessageEvent event) {
        if (config.isMaster(event.getUserId())) return true;
        if (event.getGroupId() == null || event.getGroupId() <= 0) return false;
        String role = event.getSender() == null ? null : event.getSender().getRole();
        if ("owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role)) return true;
        try {
            var result = bot.getGroupMemberInfo(event.getGroupId(), event.getUserId(), false);
            var member = result == null ? null : result.getData();
            return member != null && ("owner".equalsIgnoreCase(member.getRole()) || "admin".equalsIgnoreCase(member.getRole()));
        } catch (RuntimeException exception) {
            log.warn("涩图权限查询失败", exception);
            return false;
        }
    }

    private void reply(Bot bot, AnyMessageEvent event, String text) {
        bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId()).text(ShiroUtils.escape(text)).build(), false);
    }
}
