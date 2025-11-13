package fun.luqing.dmws.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class SaveImg {

    /**
     * 下载图片并保存到 image 文件夹
     * @param imgUrl 图片URL
     * @return 保存后的本地文件绝对路径，失败返回 null
     */
    public static String downloadImage(String imgUrl) {
        try {
            if (imgUrl == null || imgUrl.trim().isEmpty()) {
                log.warn("下载失败：URL 为空");
                return null;
            }

            // 处理 &amp; 转义字符
            imgUrl = imgUrl.replace("&amp;", "&");
            imgUrl = URLDecoder.decode(imgUrl, StandardCharsets.UTF_8);

            URL url = new URL(imgUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(20_000);

            if (conn.getResponseCode() != 200) {
                log.error("图片下载失败，HTTP状态码：{}", conn.getResponseCode());
                return null;
            }

            // 创建 image 文件夹
            File imageDir = new File("image");
            if (!imageDir.exists() && !imageDir.mkdirs()) {
                log.error("无法创建 image 文件夹");
                return null;
            }

            // 根据时间生成文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String absolutePath = getString(timestamp, imageDir, conn);
            //log.info("图片下载成功：{}", absolutePath);
            return absolutePath;

        } catch (Exception e) {
            log.error("图片下载异常：{}", e.getMessage(), e);
            return null;
        }
    }

    private static String getString(String timestamp, File imageDir, HttpURLConnection conn) throws IOException {
        String fileName = "img_" + timestamp + ".jpg";
        File outputFile = new File(imageDir, fileName);

        // 下载保存
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        return outputFile.getAbsolutePath();
    }
}
