package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.GroupPokeNoticeHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.PrivatePokeNoticeHandler;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import com.mikuac.shiro.dto.event.notice.PokeNoticeEvent;
import com.mikuac.shiro.enums.AtEnum;
import com.mikuac.shiro.enums.MsgTypeEnum;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.entity.dmw.AiContext;
import fun.luqing.dmws.enums.AiModelType;
import fun.luqing.dmws.enums.AiStatus;
import fun.luqing.dmws.repository.dmw.AiContextRepository;
import fun.luqing.dmws.service.old.AiChatService;
import fun.luqing.dmws.service.old.CheckTTSStatus;
import fun.luqing.dmws.service.old.TTSService;
import fun.luqing.dmws.common.utils.AiResponse;
import fun.luqing.dmws.common.utils.AiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;


@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class DmwAiPlugin {

    private final AiUtil aiUtil;
    private final AiContextRepository aiContextRepository;
    private final ConfigManager configManager;
    private final AiChatService aiChatService;
    private final CheckTTSStatus checkTTSStatus;
    private final TTSService ttsService;
    private final HelpPlugin helpPlugin;



    /**
     * /clear 命令，只删除非 system 的对话内容，保留 system
     */

    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "/clear")
    @Async("taskExecutor")
    @CommandInfo(cmd ="/clear",desc = "清除你的AI对话上下文（保留自定义设定）")
    public void clearNonSystem(Bot bot, AnyMessageEvent event) {
        try {
            long userId = event.getUserId();
            AiContext context = aiContextRepository.findById(userId).orElse(null);

            if (context == null) {
                bot.sendMsg(event,
                        MsgUtils.builder()
                                .reply(event.getMessageId())
                                .text("没有上下文记录，无需清除。")
                                .build(),
                        false);
                log.info("用户 {} 尝试清除不存在的上下文", userId);
                return;
            }

            JSONArray messages = aiUtil.getContextMessages(context);
            // 只保留 system 消息
            JSONArray newMessages = new JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if ("system".equals(msg.getString("role"))) {
                    newMessages.put(msg);
                }
            }
            context.setContext(aiUtil.serializeContext(newMessages));  // Assuming serializeContext is now public or accessible; if not, use newMessages.toString() directly
            context.setUpdatedAt(LocalDateTime.now());
            aiContextRepository.save(context);

            bot.sendMsg(event,
                    MsgUtils.builder()
                            .reply(event.getMessageId())
                            .text("已清除非 system 的对话内容。")
                            .build(),
                    false);
            log.info("用户 {} 已清除非 system 的对话内容", userId);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 处理 /allclear 命令，管理员清除所有用户的对话上下文。
     *
     * @param bot   机器人实例
     * @param event 消息事件
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "/allclear")
    @Async("taskExecutor")
    @CommandInfo(cmd = "/allclear",desc = "管理员专用，清除所有对话内容")
    public void allClear(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        if (! configManager.getDmwConfig().isMaster(userId)) {
            //bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId()).text("你没有权限执行此操作。").build(), false);
            log.warn("用户 {} 尝试执行全清除操作，但没有权限。", userId);
            return;
        }

        long count = aiContextRepository.count();
        if (count > 0) {
            aiContextRepository.deleteAll();
            bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId()).text("已清除所有用户的对话上下文，共 " + count + " 条记录。").build(), false);
            log.info("管理员 {} 清除了所有上下文，共 {} 条。", userId, count);
        } else {
            bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId()).text("没有可清除的上下文记录。").build(), false);
            log.info("管理员 {} 执行全清除，但数据库为空。", userId);
        }
    }




    /**
     * 处理切换模型命令，允许用户切换他们正在使用的AI模型。
     *
     * @param bot   机器人实例
     * @param event 消息事件
     */
    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "切换模型 ")
    @Async("taskExecutor")
    @CommandInfo(startWith = "切换模型",desc = "切换你正在使用的AI模型，例如：切换模型 deepseek\\n目前可支持的模型有")
    public void switchModel(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        String rawMessage = event.getRawMessage().trim();

        // 提取模型名
        String[] parts = rawMessage.split("\\s+");
        if (parts.length < 2) {
            bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId()).text("请指定要切换的模型，例如：/切换模型 deepseek").build(), false);
            return;
        }

        String modelName = parts[1].trim();

        try {
            AiModelType modelType = AiModelType.fromString(modelName);
            String modelValue = modelType.getValue();

            // 查询是否已有记录
            AiContext context = aiContextRepository.findById(userId).orElse(null);

            if (context == null) {
                // 不存在则创建新记录
                context = new AiContext();
                context.setUserId(userId);
                context.setModel(modelValue);
                context.setContext(""); // 初始化空上下文
            } else {
                context.setModel(modelValue);
            }

            aiContextRepository.save(context);
            bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId())
                    .text("已将你的 AI 模型切换为：" + modelValue).build(), false);

            log.info("用户 {} 成功切换模型为 {}", userId, modelValue);

        } catch (IllegalArgumentException e) {
            StringBuilder availableModels = new StringBuilder();
            for (AiModelType type : AiModelType.values()) {
                availableModels.append("- ").append(type.getValue()).append("\n");
            }
            bot.sendMsg(event, MsgUtils.builder().reply(event.getMessageId())
                    .text("模型名无效，请从以下可用模型中选择：\n" + availableModels).build(), false);
        }
    }

    /**
     * 处理与AI的对话，用户可以通过@机器人的方式与AI聊天，此操作会消耗用户的虚拟货币。
     *
     * @param bot   机器人实例
     * @param event 消息事件
     */
    @AnyMessageHandler
    @MessageHandlerFilter(at = AtEnum.NEED,types = MsgTypeEnum.text)
    @Async("taskExecutor")
    public void talkWithAi(Bot bot, AnyMessageEvent event) {


        long userId = event.getUserId();
        String name = event.getSender().getNickname();
        String message = event.getRawMessage()
                .replaceFirst("^(\\[[^]]*]\\s*)+", "")
                .trim();
        if(helpPlugin.isCommandExist(message)){
            return;
        }

        if (message.isEmpty()) {
            message = "（该用户什么都没有说）";
        }
        if (event.getMessageType().equals("private")) {
            log.info("私聊消息 {}:{}", name, message);
        } else {
            log.info("{} {}:{}", event.getGroupId(), name, message);
        }

        AiResponse response = aiChatService.talkWithAi(userId, name, message);

        String replyText = response.status() == AiStatus.SUCCESS
                ? response.response()
                : (response.errorMessage() != null ? response.errorMessage() : "AI处理失败，请稍后重试。");

        if (checkTTSStatus.check()){
            String voiceUrl=ttsService.synthesize(replyText);
            bot.sendMsg(event,MsgUtils.builder().voice(voiceUrl).build(),false
                );
            return;
        }

        if (event.getMessageType().equals("private")) {
            bot.sendMsg(event,replyText,false);
        }else {
            bot.sendMsg(event,
                    MsgUtils.builder()
                            .reply(event.getMessageId())
                            .text(replyText)
                            .build(),
                    false);
        }

    }


    @GroupPokeNoticeHandler
    @Async("taskExecutor")
    public void groupPokeHandler(Bot bot, PokeNoticeEvent event){
        if (event.getTargetId().equals(configManager.getDmwConfig().getBot_id())) {
            long userId = event.getUserId();
            long groupId = event.getGroupId();

            String nickname = bot.getGroupMemberInfo(groupId, userId, false).getData().getNickname();

            log.info("群聊 {} [{}]戳戳事件",groupId,nickname);
            String message = "(摸了摸你的头并拔了一根呆毛)";
            AiResponse response = aiChatService.talkWithAi(userId, nickname, message);

            String replyText = response.status() == AiStatus.SUCCESS
                    ? response.response()
                    : (response.errorMessage() != null ? response.errorMessage() : "AI处理失败，请稍后重试。");

            bot.sendGroupMsg(groupId, replyText, false);
        }
    }

    @PrivatePokeNoticeHandler
    @Async("taskExecutor")
    public void privatePokeHandler(Bot bot, PokeNoticeEvent event){


        long userId = event.getUserId();

        String nickname = bot.getFriendList()
                .getData()
                .stream()
                .filter(friend -> friend.getUserId() == userId)
                .map(friend -> friend.getRemark() != null && !friend.getRemark().isEmpty()
                        ? friend.getRemark()
                        : friend.getNickname())
                .findFirst()
                .orElse("未知好友");
        String message = "(摸了摸你的头并拔了一根呆毛)";

        log.info("[{}] 私聊戳戳事件",nickname);

        AiResponse response = aiChatService.talkWithAi(userId, nickname, message);

        String replyText = response.status() == AiStatus.SUCCESS
                ? response.response()
                : (response.errorMessage() != null ? response.errorMessage() : "AI处理失败，请稍后重试。");

        bot.sendPrivateMsg(userId,replyText,false);
    }


    /**
     * 查询设定命令，例如：/getconfig
     * 返回用户当前的 system content
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "当前设定")
    @Async("taskExecutor")
    @CommandInfo(cmd = "当前设定",desc = "查询你当前的系统设定内容")
    public void getConfig(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        AiContext context = findOrCreateAiContext(userId);
        JSONArray messages = aiUtil.getContextMessages(context);
        String systemContent = getSystemContent(messages);

        log.info("当前设定 查询 触发");

        List<String> msgList = new ArrayList<String>();
        msgList.add("当前系统设定为");
        msgList.add(systemContent);
        List<Map<String, Object>> forwardMsg = ShiroUtils.generateForwardMsg(configManager.getDmwConfig().getBot_id(),configManager.getDmwConfig().getBot_name(), msgList);
        bot.sendForwardMsg(event,forwardMsg);
    }

    private String getSystemContent(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if ("system".equals(msg.getString("role"))) {
                return msg.getString("content");
            }
        }
        return ""; // or handle appropriately if no system message
    }

    /**
     * 修改设定命令，修改设定 这里是新的系统内容
     * 如果没有记录则新增
     */
    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "修改设定 ")
    @Async("taskExecutor")
    @CommandInfo(startWith ="修改设定 ",desc = "修改你的系统设定内容")
    public void setConfig(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        String rawMessage = event.getRawMessage().trim();
        String newContent = rawMessage.substring("修改设定 ".length()).trim();

        if (newContent.isEmpty()) {
            bot.sendMsg(event,
                    MsgUtils.builder().reply(event.getMessageId()).text("系统内容不能为空").build(),
                    false);
            return;
        }

        AiContext context = findOrCreateAiContext(userId);
        JSONArray messages = aiUtil.getContextMessages(context);
        updateSystemContent(messages, newContent);

        context.setContext(aiUtil.serializeContext(messages));  // Assuming serializeContext is now public or accessible; if not, use messages.toString() directly
        context.setUpdatedAt(LocalDateTime.now());
        aiContextRepository.save(context);

        log.info("修改设定 指令触发");

        bot.sendMsg(event,
                MsgUtils.builder()
                        .reply(event.getMessageId())
                        .text("已更新系统设定")
                        .build(),
                false);
    }

    private void updateSystemContent(JSONArray messages, String newContent) {
        boolean found = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if ("system".equals(msg.getString("role"))) {
                msg.put("content", newContent);
                found = true;
                break;
            }
        }
        if (!found) {
            // If no system message, add one at the beginning
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", newContent);
            messages.put(0, systemMsg);
        }
    }

    /**
     * 恢复默认系统设定命令，例如：/resetconfig
     * 将 system content 恢复为默认值
     */
    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "恢复默认设定")
    @Async("taskExecutor")
    @CommandInfo(cmd ="恢复默认设定",desc = "将你的系统设定恢复为默认")
    public void resetConfig(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        AiContext context = aiContextRepository.findById(userId)
                .orElseGet(() -> {
                    AiContext ctx = new AiContext();
                    ctx.setUserId(userId);
                    ctx.setCreatedAt(LocalDateTime.now());
                    return ctx;
                });

        JSONArray messages = aiUtil.getContextMessages(context);

        boolean updated = false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if ("system".equals(msg.getString("role"))) {
                msg.put("content", configManager.getDmwConfig().getAi_character());
                updated = true;
                break;
            }
        }

        if (!updated) {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", configManager.getDmwConfig().getAi_character());
            messages.put(0, sysMsg); // 放到最前面
        }

        context.setContext(aiUtil.serializeContext(messages));
        context.setUpdatedAt(LocalDateTime.now());
        aiContextRepository.save(context);
        log.info("恢复默认设定 指令触发");

        bot.sendMsg(event,
                MsgUtils.builder()
                        .reply(event.getMessageId())
                        .text("已恢复默认系统设定。")
                        .build(),
                false);
    }




    private AiContext findOrCreateAiContext(long userId) {
        // 先尝试查询已有上下文
        Optional<AiContext> existing = aiContextRepository.findById(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 不存在则新建
        AiContext newCtx = new AiContext();
        newCtx.setUserId(userId);
        newCtx.setCreatedAt(LocalDateTime.now());
        newCtx.setModel(configManager.getDmwConfig().getDefault_model());
        newCtx.setContext("");

        try {
            // 尝试保存
            return aiContextRepository.saveAndFlush(newCtx);
        } catch (DataIntegrityViolationException e) {
            // 若并发情况下已被别的线程插入，则重新查询返回
            return aiContextRepository.findById(userId).orElseThrow();
        }
    }




    private String getSystemContent(List<Map<String, String>> messages) {
        return messages.stream()
                .filter(m -> "system".equals(m.get("role")))
                .map(m -> m.get("content"))
                .findFirst()
                .orElse("(未设置系统内容)");
    }

    private void updateSystemContent(List<Map<String, String>> messages, String newContent) {
        boolean updated = false;
        for (Map<String, String> msg : messages) {
            if ("system".equals(msg.get("role"))) {
                msg.put("content", newContent);
                updated = true;
                break;
            }
        }
        if (!updated) {
            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", newContent);
            messages.add(0, systemMessage); // 放到最前面
        }
    }

    private static JSONArray buildChatVisionJson(String[] urls, String text) {
        JSONArray array = new JSONArray();

        JSONObject innerObj = new JSONObject();
        innerObj.put("role", "user");

        JSONArray contentArray = new JSONArray();

        for (String url : urls) {
            JSONObject imageObj = new JSONObject();
            imageObj.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", url);
            imageObj.put("image_url", imageUrl);

            contentArray.put(imageObj);
        }

        JSONObject textObj = new JSONObject();
        textObj.put("type", "text");
        textObj.put("text", text);

        contentArray.put(textObj);

        innerObj.put("content", contentArray);

        array.put(innerObj);

        return array;
    }

}