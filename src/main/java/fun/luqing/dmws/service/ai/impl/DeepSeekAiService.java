package fun.luqing.dmws.service.ai.impl;

import cn.hutool.ai.AIServiceFactory;
import cn.hutool.ai.ModelName;
import cn.hutool.ai.core.AIConfigBuilder;
import cn.hutool.ai.core.Message;
import cn.hutool.ai.model.deepseek.DeepSeekService;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.service.ai.AiResult;
import fun.luqing.dmws.service.ai.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 模型实现类
 */
@Slf4j
@Service("deepSeekAiService")
@RequiredArgsConstructor
public class DeepSeekAiService implements AiService {

    private final ConfigManager configManager;

    @Override
    public AiResult chat(JSONArray messages) {
        try {
            String apiKey = configManager.getDmwConfig().getDeepseek_api_key();
            String model = configManager.getDmwConfig().getDeepseek_model();

            DeepSeekService deepSeekService = AIServiceFactory.getAIService(
                    new AIConfigBuilder(ModelName.DEEPSEEK.getValue())
                            .setApiKey(apiKey)
                            .setModel(model)
                            .build(),
                    DeepSeekService.class
            );



            // 返回 JSON 串，统一使用 org.json 解析
            JSONObject response = new JSONObject(deepSeekService.chat(String.valueOf(messages)));

            String content = response
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

            long totalTokens = response
                    .getJSONObject("usage")
                    .getLong("total_tokens");

            return new AiResult(content, totalTokens);

        } catch (Exception e) {
            log.error("DeepSeek 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用 DeepSeek 出错：" + e.getMessage(), e);
        }
    }
}
