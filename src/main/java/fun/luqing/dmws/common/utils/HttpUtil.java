package fun.luqing.dmws.common.utils;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * HttpUtil 类提供了执行 HTTP 请求的方法，支持 GET 和 POST 方法，并且可以配置代理。
 * 该类的所有方法都会返回一个 JSONObject 对象，即使在请求过程中发生异常也会返回包含错误信息的 JSON 对象。
 */
public class HttpUtil {



    public static JSONObject request(String method, String urlStr, JSONObject params, String proxyHost, int proxyPort) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);

            // 如果设置了代理
            if (proxyHost != null && !proxyHost.isEmpty()) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }

            conn.setRequestMethod(method.toUpperCase());
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoInput(true);

            // 如果是 POST 就写入 JSON 参数
            if ("POST".equalsIgnoreCase(method) && params != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(params.toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            // 读取响应
            InputStream inputStream = conn.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            System.out.println(response.toString());

            // 直接解析为 JSONObject
            return new JSONObject(response.toString());

        } catch (Exception e) {
            // 发生错误也返回 JSON 格式
            JSONObject errorJson = new JSONObject();
            errorJson.put("error", e.getMessage());
            return errorJson;
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    public static JSONArray postJSONArray(String url, JSONObject params) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            if (params != null) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(params.toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            reader = new BufferedReader(new InputStreamReader(
                    conn.getResponseCode() < HttpURLConnection.HTTP_BAD_REQUEST
                            ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
            ));

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String respText = sb.toString().trim();

            // 判断返回是否是 JSON 数组
            if (respText.startsWith("[")) {
                return new JSONArray(respText);
            } else {
                JSONArray errorArray = new JSONArray();
                JSONObject err = new JSONObject();
                err.put("error", "API返回非JSONArray: " + respText);
                errorArray.put(err);
                return errorArray;
            }

        } catch (Exception e) {
            JSONArray errorArray = new JSONArray();
            JSONObject err = new JSONObject();
            err.put("error", e.getMessage());
            errorArray.put(err);
            return errorArray;
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }
    }


    // GET 请求
    public static JSONObject get(String url, JSONObject params) {
        if (params != null && !params.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            if (!url.contains("?")) sb.append("?");
            for (String key : params.keySet()) {
                sb.append(key).append("=").append(URLEncoder.encode(params.get(key).toString(), java.nio.charset.StandardCharsets.UTF_8)).append("&");
            }
            url = sb.substring(0, sb.length() - 1);
        }
        return request("GET", url, null, null, 0);
    }

    // POST 请求
    public static JSONObject post(String url, JSONObject params) {
        return request("POST", url, params, null, 0);
    }

    // POST 带代理
    public static JSONObject postWithProxy(String url, JSONObject params, String proxyHost, int proxyPort) {
        return request("POST", url, params, proxyHost, proxyPort);
    }
}

