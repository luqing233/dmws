package fun.luqing.dmws.common.utils;

import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.entity.dmw.AiContext;
import fun.luqing.dmws.enums.AiModelType;
import fun.luqing.dmws.repository.dmw.AiContextRepository;
import fun.luqing.dmws.service.old.ai.AiResult;
import fun.luqing.dmws.service.old.ai.AiService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AI 聊天工具类
 * 支持多模型（DeepSeek、DouBao 等）
 * 计划重构
 *
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiUtil {

    private final AiContextRepository aiContextRepository;
    private final ConfigManager configManager;
    private final ApplicationContext applicationContext;

    private String defaultModel;
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.defaultModel = AiModelType.DEEPSEEK.getValue();
    }

    public AiResponse chat(Long userId, String message) {
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("用户 {} 正在处理中", userId);
            return AiResponse.busy();
        }

        try {
            // ① 获取或初始化上下文
            AiContext aiContext = aiContextRepository.findById(userId)
                    .orElseGet(() -> {
                        AiContext ctx = new AiContext();
                        ctx.setUserId(userId);
                        ctx.setModel(defaultModel);
                        ctx.setCreatedAt(LocalDateTime.now());
                        return ctx;
                    });

            // ② 根据模型动态选择 Bean
            AiModelType modelType = AiModelType.fromString(aiContext.getModel());
            AiService aiService = applicationContext.getBean(modelType.getServiceBeanName(), AiService.class);

            // ③ 读取上下文消息
            JSONArray messages = getContextMessages(aiContext);
            messages.put(createMessage("user", message));

            // ④ 调用模型服务
            AiResult result = aiService.chat(messages);

            // ⑤ 更新上下文
            messages.put(createMessage("assistant", result.content()));
            aiContext.setContext(serializeContext(messages));
            aiContext.setUpdatedAt(LocalDateTime.now());
            aiContextRepository.save(aiContext);

            return AiResponse.success(result.content(), result.totalTokens());

        } catch (Exception e) {
            log.error("AI 对话出错: {}", e.getMessage(), e);
            return AiResponse.error("AI 服务出错：" + e.getMessage());
        } finally {
            lock.unlock();
            userLocks.remove(userId, lock);
        }
    }

    public JSONArray getContextMessages(AiContext aiContext) {
        JSONArray messages = new JSONArray();
        if (aiContext.getContext() == null || aiContext.getContext().isEmpty()) {
            messages.put(createMessage("system", configManager.getDmwConfig().getAi_character()));
            return messages;
        }
        try {
            messages = new JSONArray(aiContext.getContext());
        } catch (Exception e) {
            log.error("解析上下文失败：{}", e.getMessage(), e);
        }
        return messages;
    }

    private JSONObject createMessage(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    public String serializeContext(JSONArray messages) {
        // 若超过40条，删除索引1的元素（保留system提示与后续上下文）
        if (messages.length() > 40) {
            messages.remove(1);
        }
        return messages.toString();
    }

}