package fun.luqing.dmws.service;

import fun.luqing.dmws.repository.dmw.TomatoBookContentRepository;
import io.quickchart.QuickChart;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.List;

import static fun.luqing.dmws.utils.TempJsonUtil.getJsonFromTemp;

@Service
@RequiredArgsConstructor
public class TomatoBookChartService {

    private final TomatoBookContentRepository tomatoBookContentRepository;

    public String generateBook30DaysChart(String bookId,String bookName) {

        // 查询最近 30 天数据
        List<Object[]> rows = tomatoBookContentRepository.findStatsLast30Days(bookId);

        List<String> dates = new ArrayList<>();
        List<Integer> chapterCounts = new ArrayList<>();
        List<Integer> wordCounts = new ArrayList<>();

        for (Object[] r : rows) {
            dates.add(String.valueOf(r[0]));
            chapterCounts.add(((Number) r[1]).intValue());
            wordCounts.add(((Number) r[2]).intValue());
        }

        String chartTitle ="《"+ bookName + "》 最近三十天更新情况";
        String wordCountLabel = "更新字数";
        String chapterCountLabel = "更新章节数";

        JSONObject tempJson = getJsonFromTemp("30.json");


        updateDataset(tempJson, 0, wordCounts, wordCountLabel);
        updateDataset(tempJson, 1, chapterCounts, chapterCountLabel);
        tempJson.getJSONObject("data").put("labels", dates);
        tempJson.getJSONObject("options").getJSONObject("title").put("text",chartTitle);


        QuickChart chart = new QuickChart();
        chart.setWidth(1000);
        chart.setHeight(600);
        chart.setBackgroundColor("#dce4ef");
        chart.setVersion("2");
        chart.setConfig(String.valueOf(tempJson));

        return chart.getUrl();
    }

    private void updateDataset(JSONObject tempJson, int index, List<Integer> data, String label) {
        JSONObject dataset = tempJson.getJSONObject("data").getJSONArray("datasets").getJSONObject(index);
        dataset.put("data", data);
        dataset.put("label", label);
    }
}
