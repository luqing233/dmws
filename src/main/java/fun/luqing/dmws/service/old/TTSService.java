package fun.luqing.dmws.service.old;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
public class TTSService {

    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    private static final String[] fallbackVersions = {"v4", "v2Pro", "v2ProPlus", "v2"};

    /**
     * 将文字合成语音，返回本地或下载 URL
     */
    public String synthesize(String text, String model) {

        logger.info("TTS合成请求: model={}, text={}", model, text);

        for (String version : fallbackVersions) {
            JSONObject json = buildJson(text, model, version);

            try {
                String resp = sendPostRequest("http://127.0.0.1:8000/infer_single", json.toString());
                String url = new JSONObject(resp).optString("audio_url", "");
                if (!url.isBlank()) {
                    // 返回本地路径或可下载链接
                    return url.replace("http://0.0.0.0:8000/outputs/", "E:\\SoVITS\\GPT-SoVITS-0725-cu124\\outputs\\");
                }
            } catch (IOException e) {
                logger.error("无法连接本地 TTS 服务: {}", e.getMessage());
                return "-1";
            } catch (Exception e) {
                logger.warn("版本 {} 失败，尝试下一个版本…", version);
            }
        }

        return "0"; // 所有版本都失败
    }


    public String synthesize(String text){
        return synthesize(text,"银狼");
    }


    private JSONObject buildJson(String text, String model, String version) {
        JSONObject json = new JSONObject();
        json.put("model_name", model);
        json.put("prompt_text_lang", "中文");
        json.put("emotion", "默认");
        json.put("text", text);
        json.put("text_lang", "中文");
        json.put("top_k", 10);
        json.put("top_p", 1);
        json.put("temperature", 1);
        json.put("text_split_method", "按标点符号切");
        json.put("batch_size", 10);
        json.put("batch_threshold", 0.75);
        json.put("split_bucket", true);
        json.put("speed_facter", model.equals("银狼") ? 0.95 : 1);
        json.put("fragment_interval", 0.3);
        json.put("media_type", "wav");
        json.put("parallel_infer", true);
        json.put("repetition_penalty", 1.35);
        json.put("seed", -1);
        json.put("sample_steps", 16);
        json.put("if_sr", false);
        json.put("access_token", "");
        json.put("version", version);
        return json;
    }

    private String sendPostRequest(String urlString, String jsonInput) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) response.append(line.trim());
            return response.toString();
        }
    }
}