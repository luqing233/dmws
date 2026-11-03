package fun.luqing.dmws.client;

import fun.luqing.dmws.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TomatoApiClientTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ConfigManager configManager;

    @Mock
    private McpClient downloader;

    @Test
    void doesNotUseAChapterWithAChangedItemId() {
        TomatoApiClient client = new TomatoApiClient(configManager);
        ReflectionTestUtils.setField(client, "downloader", downloader);

        ObjectNode oldChapter = MAPPER.createObjectNode();
        oldChapter.put("item_id", "old-id");
        oldChapter.put("title", "第79章");
        oldChapter.put("content", "旧章节正文");

        when(downloader.callTool(eq("tnd_read_downloaded_chapter"), any())).thenReturn(oldChapter);

        assertNull(client.getChapterContent("book-id", "new-id", 83));
    }
}
