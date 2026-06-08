package fun.luqing.dmws.client;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class TomatoApiClient {


//https://qkfqapi.vv9v.cn/api/search
    private static final String DETAIL_API_2 = "http://101.35.133.34:5000/api/detail?book_id=";

    private static final String CHAPTER_CONTENT_API = "http://101.35.133.34:5000/api/raw_full?item_id=";

    private static final String BOOK_CATALOG_API = "http://101.35.133.34:5000/api/book?book_id=";

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    private final RestTemplate restTemplate;

    public TomatoApiClient() {
        this.restTemplate = createRestTemplate();
    }

    /**
     * 创建并配置一个RestTemplate实例，用于进行HTTP请求。
     * 此方法设置连接和读取超时时间为30000毫秒。
     *
     * @return 配置好的RestTemplate实例
     */
    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    // ===============================
    //   通用工具方法
    // ===============================

    /**
     * 发送HTTP GET请求并获取响应内容。
     *
     * @param url 请求的URL地址
     * @return 响应的内容，如果请求失败则返回null
     */
    private String httpGet(String url) {
        try {
            return restTemplate.getForObject(url, String.class);
        } catch (Exception e1) {
            log.warn("HTTP 请求失败（第一次）: url={} err={}", url, e1.getMessage());

            // 等待 3 秒后重试一次
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}

            try {
                //log.info("重试请求: {}", url);
                return restTemplate.getForObject(url, String.class);
            } catch (Exception e2) {
                log.error("HTTP 请求失败（第二次）: url={} err={}", url, e2.getMessage());
                return null;
            }
        }
    }


    /**
     * 将给定的纪元时间转换为"Asia/Shanghai"时区的格式化日期字符串。
     *
     * @param epoch 纪元时间（秒）
     * @return 如果转换成功，则返回格式化的日期字符串，否则返回空字符串
     */
    private String formatEpoch(long epoch) {
        if (epoch <= 0) return "";
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(epoch),
                    ZoneId.of("Asia/Shanghai")
            ).format(FORMATTER);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从给定的父JSONObject中安全地检索指定键的子JSONObject。
     * 如果未找到子JSONObject，则记录警告并返回null。
     *
     * @param obj 要从中检索子项的父JSONObject。
     * @param key 要检索的子JSONObject的键。
     * @return 如果找到子JSONObject则返回，如果父JSONObject中不存在该键则返回null。
     */
    private JSONObject safe(JSONObject obj, String key) {
        JSONObject child = obj.optJSONObject(key);
        if (child == null) log.warn("JSON 缺少字段 {}", key);
        return child;
    }

    
    /**
     * 从数据源2获取书籍详情。
     * @param bookId 书籍ID
     * @return 返回包含书籍名称、最新章节标题、最新章节ID、最后更新时间和总字数的TomatoUpdateRecord对象，如果获取失败则返回null。
     */
    public TomatoUpdateRecord getBookDetail(String bookId) {
        String body = httpGet(DETAIL_API_2 + bookId);
        if (body == null) return null;

        try {
            JSONObject root = new JSONObject(body);
            JSONObject dataWrapper = safe(root, "data");
            if (dataWrapper == null) return null;

            JSONObject data = safe(dataWrapper, "data");
            if (data == null) return null;

            return new TomatoUpdateRecord(
                    data.optString("book_name"),
                    data.optString("last_chapter_title"),
                    data.optString("last_chapter_item_id"),
                    formatEpoch(data.optLong("last_publish_time")),
                    data.optLong("word_number")
            );

        } catch (Exception e) {
            log.error("解析数据源 2 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据章节ID获取特定章节的内容。
     *
     * @param chapterId 要获取的章节的唯一标识符。
     * @return 包含章节详细信息和内容的TomatoContentRecord对象，如果无法获取章节则返回null。
     */
    public TomatoContentRecord getChapterContent(String chapterId) {
        String body = httpGet(CHAPTER_CONTENT_API + chapterId);
        if (body == null) return null;

        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = safe(root, "data");
            if (data == null) return null;

            JSONObject novel = safe(data, "novel_data");
            if (novel == null) return null;

            String updateTime = formatEpoch(
                    novel.optLong("first_pass_time", 0)
            );

            return new TomatoContentRecord(
                    novel.optString("book_id"),
                    novel.optString("book_name"),
                    novel.optInt("real_chapter_order"),
                    novel.optString("title"),
                    data.optString("content"),
                    updateTime,
                    novel.optInt("chapter_word_number")
            );

        } catch (Exception e) {
            log.error("解析章节内容失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取指定书籍的所有章节信息（目录）。
     * @param bookId 书籍的唯一标识符
     * @return 返回一个包含所有章节记录的列表，如果获取失败则返回空列表
     */
    public List<TomatoChapterRecord> getChapters(String bookId) {
        List<TomatoChapterRecord> list = new ArrayList<>();

        String body = httpGet(BOOK_CATALOG_API + bookId);
        if (body == null) return list;

        try {
            JSONObject root = new JSONObject(body);
            JSONObject dataWrapper = safe(root, "data");
            if (dataWrapper == null) return list;

            JSONObject data = safe(dataWrapper, "data");
            if (data == null) return list;

            JSONArray volumes = data.optJSONArray("chapterListWithVolume");
            if (volumes == null) return list;

            for (int i = 0; i < volumes.length(); i++) {
                JSONArray volume = volumes.optJSONArray(i);
                if (volume == null) continue;

                for (int j = 0; j < volume.length(); j++) {
                    JSONObject ch = volume.optJSONObject(j);
                    if (ch == null) continue;

                    String firstPassTime = formatEpoch(
                            ch.optLong("firstPassTime")
                    );

                    list.add(new TomatoChapterRecord(
                            ch.optString("itemId"),
                            ch.optString("title"),
                            ch.optString("volume_name"),
                            ch.optBoolean("isChapterLock"),
                            ch.optBoolean("isPaidPublication"),
                            ch.optBoolean("isPaidStory"),
                            ch.optInt("needPay"),
                            ch.optInt("realChapterOrder"),
                            firstPassTime
                    ));
                }
            }

        } catch (Exception e) {
            log.error("解析章节目录失败: {}", e.getMessage());
        }

        return list;
    }
}
