package fun.luqing.dmws.service.old.ai.impl;

import cn.hutool.ai.AIServiceFactory;
import cn.hutool.ai.ModelName;
import cn.hutool.ai.core.AIConfigBuilder;
import cn.hutool.ai.model.doubao.DoubaoService;
import cn.hutool.core.img.ImgUtil;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.service.old.ai.AiResult;
import fun.luqing.dmws.service.old.ai.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * DouBao 模型实现类（支持文本、图像理解、视频生成）
 */
@Slf4j
@Service("douBaoAiService")
@RequiredArgsConstructor
public class DouBaoAiService implements AiService {

    private final ConfigManager configManager;

    @Override
    public AiResult chat(JSONArray messages) {
        try {
            String apiKey = configManager.getDmwConfig().getDoubao_api_key();
            String model = configManager.getDmwConfig().getDoubao_model();

            return getAiResult(messages, apiKey, model);

        } catch (Exception e) {
            log.error("DouBao 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用 DouBao 出错：" + e.getMessage(), e);
        }
    }


    public AiResult chatVision(JSONArray messages) {
        try {
            String apiKey = configManager.getDmwConfig().getDoubao_api_key();
            String model = "doubao-seed-1-6-vision-250815";
            return getAiResult(messages, apiKey, model);

        } catch (Exception e) {
            log.error("DouBao 图像识别失败: {}", e.getMessage(), e);
            throw new RuntimeException("DouBao 图像识别出错：" + e.getMessage(), e);
        }
    }

    private AiResult getAiResult(JSONArray messages, String apiKey, String model) {
        DoubaoService doubaoService = AIServiceFactory.getAIService(
                new AIConfigBuilder(ModelName.DOUBAO.getValue())
                        .setApiKey(apiKey)
                        .setModel(model)
                        .build(),
                DoubaoService.class
        );

        JSONObject response = new JSONObject(doubaoService.chat(String.valueOf(messages)));
        //System.out.println(response);

        String content = response
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

        long totalTokens = response
                .optJSONObject("usage") != null
                ? response.getJSONObject("usage").optLong("total_tokens", 0L)
                : 0L;

        return new AiResult(content, totalTokens);
    }

    /**
     * 🧩 将本地图片转换为 Base64 Data URI
     */
    public String toBase64(String imagePath) {
        try {
            BufferedImage img = ImageIO.read(new File(imagePath));
            return ImgUtil.toBase64DataUri(img, "png");
        } catch (Exception e) {
            throw new RuntimeException("图片转 Base64 出错：" + e.getMessage(), e);
        }
    }

    /**
     * 🎬 创建视频任务
     * @param prompt 视频描述
     * @param imageUrl 可选封面图
     *//*
    public String createVideoTask(String prompt, String imageUrl) {
        try {
            String apiKey = configManager.getDmwConfig().getDoubao_api_key();
            String endpointId = configManager.getDmwConfig().getDoubao_video_endpoint(); // 需在配置文件中定义

            DoubaoService doubaoService = AIServiceFactory.getAIService(
                    new AIConfigBuilder(ModelName.DOUBAO.getValue())
                            .setApiKey(apiKey)
                            .setModel(endpointId)
                            .build(),
                    DoubaoService.class
            );

            return doubaoService.videoTasks(prompt, imageUrl);

        } catch (Exception e) {
            log.error("DouBao 视频生成任务创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("DouBao 视频生成任务出错：" + e.getMessage(), e);
        }
    }

    *//**
     * 📋 查询视频任务状态
     * @param taskId 任务ID
     *//*
    public String getVideoTaskInfo(String taskId) {
        try {
            String apiKey = configManager.getDmwConfig().getDoubao_api_key();

            DoubaoService doubaoService = AIServiceFactory.getAIService(
                    new AIConfigBuilder(ModelName.DOUBAO.getValue())
                            .setApiKey(apiKey)
                            .build(),
                    DoubaoService.class
            );

            return doubaoService.getVideoTasksInfo(taskId);

        } catch (Exception e) {
            log.error("DouBao 视频任务查询失败: {}", e.getMessage(), e);
            throw new RuntimeException("DouBao 视频任务查询出错：" + e.getMessage(), e);
        }
    }*/
}
