package fun.luqing.dmws.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
@Slf4j
@Getter
public class ConfigManager {

    private static final String CONFIG_PATH = "config/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DmwConfig dmwConfig;

    @PostConstruct
    public void initConfig() {
        try {
            File file = new File(CONFIG_PATH);

            if (!file.exists()) {
                createDefaultConfig(file);
            } else {
                loadConfig(file);
            }
        } catch (IOException e) {
            log.error("初始化配置失败", e);
            // 初始化一个默认配置以防止空指针
            dmwConfig = new DmwConfig();
        }
    }

    private void createDefaultConfig(File file) throws IOException {
        file.getParentFile().mkdirs();
        dmwConfig = new DmwConfig();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, dmwConfig);
        log.info("默认配置已写入: {}", CONFIG_PATH);
    }

    private void loadConfig(File file) throws IOException {
        dmwConfig = MAPPER.readValue(file, DmwConfig.class);
        log.info("读取配置成功: {}", CONFIG_PATH);
    }
}