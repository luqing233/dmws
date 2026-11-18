package fun.luqing.dmws.service.old;

import fun.luqing.dmws.common.utils.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeTuServiceTests {
    @Test void doesNotHideUpstreamErrorsBehindAnEmptyDataArray() {
        try (var http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.post(anyString(), any(JSONObject.class)))
                    .thenReturn(new JSONObject().put("error", "upstream failure").put("data", new JSONArray()));
            var data = new SeTuService().getLoliconImageByKeyword(List.of("风景"), null, 0, 1, List.of("regular"), null, false);
            assertEquals("upstream failure", data.getJSONObject(0).getString("error"));
        }
    }
}
