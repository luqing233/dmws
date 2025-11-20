package fun.luqing.dmws.common.utils;


import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
@Slf4j
public class TempJsonUtil {

    private static final String TEMP_DIR = "./temp";

    /**
     * 获取 temp 文件夹下的 JSON 文件内容
     * @param fileName 文件名，例如 "data.json"
     * @return JSONObject 对象
     */
    public static JSONObject getJsonFromTemp(String fileName) {
        // 创建 temp 文件夹
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists()) {
            boolean created = tempDir.mkdirs();
            if (!created) {
                log.error("无法创建 temp 文件夹");
            }
        }

        // 指定文件
        File jsonFile = new File(tempDir, fileName);

        // 如果文件不存在，返回空的 JSONObject
        if (!jsonFile.exists()) {
            return new JSONObject();
        }

        // 读取 JSON 文件
        try (FileReader reader = new FileReader(jsonFile)) {
            JSONTokener tokener = new JSONTokener(reader);
            return new JSONObject(tokener);
        } catch (Exception e) {
            log.error(String.valueOf(e));
            return new JSONObject();
        }
    }

    /**
     * 保存 JSONObject 到 temp 文件夹
     * @param fileName 文件名
     * @param json JSONObject 对象
     */
    public static void saveJsonToTemp(String fileName, JSONObject json) {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        File jsonFile = new File(tempDir, fileName);
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(json.toString(4)); // 美化格式缩进 4
        } catch (IOException e) {
           log.error(String.valueOf(e));
        }
    }

}
