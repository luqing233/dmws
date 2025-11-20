package fun.luqing.dmws.service.old;

import fun.luqing.dmws.common.utils.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SeTuService 用于调用 API 获取 涩图
 */
@Service
public class SeTuService {

    //API 地址
    private static final String ANOSU_API_URL = "https://image.anosu.top/pixiv/json";
    private static final String LOLICON_API_URL="https://api.lolicon.app/setu/v2";




    /**
     * 调用 Lolicon API v2 获取图片
     *
     * @param tagList      标签列表
     * @param r18          0=非R18, 1=R18, 2=混合
     * @param num          返回数量，1~20
     * @param sizeList     返回图片规格，如 ["original","regular"]
     * @param proxy        图片反代服务
     * @param excludeAI    是否排除 AI 作品
     * @return JSONArray   返回 API data 数组
     */
    public JSONArray getLoliconImageByKeyword(
            List<String> tagList,
            String keyword,
            int r18,
            int num,
            List<String> sizeList,
            String proxy,
            Boolean excludeAI

    ) {
        JSONObject params = new JSONObject();

        if (tagList != null && !tagList.isEmpty()) params.put("tag", tagList);
        if (keyword != null && !keyword.isEmpty()) params.put("keyword", keyword);
        params.put("r18", r18);
        params.put("num", Math.min(Math.max(num, 1), 5));
        if (sizeList != null && !sizeList.isEmpty()) {
            params.put("size", sizeList);
        }else params.put("size", "small");
        if (proxy != null) params.put("proxy", proxy);

        if (excludeAI != null) params.put("excludeAI", excludeAI);
        // 调用 POST 请求
        JSONObject response = HttpUtil.post(LOLICON_API_URL, params);

        // 安全返回 JSONArray
        if (response.has("data") && response.get("data") instanceof JSONArray) {
            return response.getJSONArray("data");
        } else {
            JSONArray errorArray = new JSONArray();
            JSONObject err = new JSONObject();
            err.put("error", "API返回异常: " + response.toString());
            errorArray.put(err);
            return errorArray;
        }
    }

    /**
     * 调用 Anosu API 根据关键词获取图片
     * 最次选
     * @param keyword 搜索关键词，可使用 "|" 分隔多个
     * @param num     请求数量，1~30
     * @param r18     图片分级，0=全年龄, 1=R18, 2=随机
     * @param size    图片尺寸 original/regular/small
     * @param proxy   可选代理，如 "i.pixiv.re"
     * @param db      图库选择，0=新库，1=旧库
     * @return JSONObject 返回 Anosu API 的响应
     */
    public JSONArray getAnosuImageByKeyword(String keyword, int num, int r18, String size, String proxy, int db) {
        JSONObject params = new JSONObject();
        params.put("keyword", keyword);
        params.put("num", Math.min(Math.max(num, 1),6)); // 限制 1~6
        params.put("r18", r18);
        params.put("size", size != null ? size : "small");
        params.put("proxy", proxy != null ? proxy : "");
        params.put("db", db);
        return HttpUtil.postJSONArray(ANOSU_API_URL, params);
    }
}
