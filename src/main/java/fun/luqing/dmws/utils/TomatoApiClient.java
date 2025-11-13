package fun.luqing.dmws.utils;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
/*
* 计划重构
*
*
* */
@Slf4j
public class TomatoApiClient {

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    private final RestTemplate restTemplate = createRestTemplate();


    public TomatoUpdateRecord getBookDetail(String bookId) {
        int random = java.util.concurrent.ThreadLocalRandom.current().nextInt(2);
        if (random == 0) {
            return getBookDetail1(bookId);
        } else {
            return getBookDetail2(bookId);
        }
    }


    /**
     * 调用番茄API，返回数据
     * @param bookId 小说ID
     * @return TomatoUpdateInfo，失败时返回 null
     */
    public TomatoUpdateRecord getBookDetail1(String bookId) {
        String url = "https://api.cenguigui.cn/api/tomato/api/detail.php?book_id=" + bookId;

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JSONObject resp= new JSONObject(response.getBody());
            JSONObject data = resp.getJSONObject("data");
            String bookName=data.getString("original_book_name");
            String lastChapterTitle=data.getString("last_chapter_title");
            String lastChapterId=data.getString("last_chapter_item_id");


            long lastPublishTimestamp = data.getLong("last_publish_time");
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String lastPublishTime = dateFormat.format(new Date(lastPublishTimestamp * 1000));

            return new TomatoUpdateRecord(bookName, lastChapterTitle,lastChapterId, lastPublishTime);
        } catch (Exception e) {
            log.error("请求番茄API失败: {}", e.getMessage());
            return null;
        }
    }

    public TomatoUpdateRecord getBookDetail2(String bookId) {
        String url = "https://fq.shusan.cn/api/detail?book_id=" + bookId;

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JSONObject resp= new JSONObject(response.getBody());
            //System.out.println(resp);
            JSONObject data = resp.getJSONObject("data").getJSONObject("data");
            String bookName=data.getString("book_name");
            String lastChapterTitle=data.getString("last_chapter_title");
            String lastChapterId=data.getString("last_chapter_item_id");
            long lastPublishTimestamp = data.getLong("last_chapter_update_time");
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String lastPublishTime = dateFormat.format(new Date(lastPublishTimestamp * 1000));

            return new TomatoUpdateRecord(bookName, lastChapterTitle,lastChapterId, lastPublishTime);
        } catch (Exception e) {
            log.error("请求番茄API失败: {}", e.getMessage());
            return null;
        }
    }

    public TomatoContentRecord getChapterContent(String chapterId) {
        String url = "https://fq.shusan.cn/api/raw_full?item_id=" + chapterId;

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            JSONObject resp = new JSONObject(response.getBody());

            JSONObject data = resp.getJSONObject("data");
            JSONObject novelData=data.getJSONObject("novel_data");

            String bookId=novelData.getString("book_id");
            String bookName=novelData.getString("book_name");
            int realChapterOrder=novelData.getInt("real_chapter_order");
            String title=novelData.getString("title");

            String content = data.getString("content");


            String updateTime="";
            if (novelData.has("first_pass_time")) {
                try {
                    long timestamp = Long.parseLong(novelData.getString("first_pass_time"));
                    LocalDateTime dateTime = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(timestamp),
                            ZoneId.of("Asia/Shanghai")
                    );
                    updateTime = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } catch (Exception ignore) {}
            }


            int wordCount=novelData.getInt("chapter_word_number");

            return new TomatoContentRecord(bookId,bookName, realChapterOrder, title, content, updateTime, wordCount);

        } catch (Exception e) {
            log.error("获取章节内容失败: {}", e.getMessage());
            return null;
        }
    }


    public List<TomatoChapterRecord> getChapters(String bookId) {
        String url = "https://fq.shusan.cn/api/book?book_id=" + bookId;
        List<TomatoChapterRecord> chapters = new ArrayList<>();

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject root = new JSONObject(response.getBody());
            JSONObject data = root.getJSONObject("data").getJSONObject("data");

            JSONArray volumes = data.getJSONArray("chapterListWithVolume");
            for (int i = 0; i < volumes.length(); i++) {
                JSONArray volume = volumes.getJSONArray(i);
                for (int j = 0; j < volume.length(); j++) {
                    JSONObject chapterObj = volume.getJSONObject(j);

                    String firstPassTime = "";
                    if (chapterObj.has("firstPassTime")) {
                        try {
                            long timestamp = Long.parseLong(chapterObj.getString("firstPassTime"));
                            LocalDateTime dateTime = LocalDateTime.ofInstant(
                                    Instant.ofEpochSecond(timestamp),
                                    ZoneId.of("Asia/Shanghai")
                            );
                            firstPassTime = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        } catch (Exception ignore) {}
                    }


                    TomatoChapterRecord chapter = new TomatoChapterRecord(
                            chapterObj.optString("itemId", ""),
                            chapterObj.optString("title", ""),
                            chapterObj.optString("volume_name", ""),
                            chapterObj.optBoolean("isChapterLock", false),
                            chapterObj.optBoolean("isPaidPublication", false),
                            chapterObj.optBoolean("isPaidStory", false),
                            chapterObj.optInt("needPay", 0),
                            chapterObj.optInt("realChapterOrder", 0),
                            firstPassTime
                    );

                    chapters.add(chapter);
                }
            }

        } catch (Exception e) {
            log.error("获取章节列表失败: {}", e.getMessage());
        }
        return chapters;
    }

}
