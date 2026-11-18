package fun.luqing.dmws.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dmws.fishing-web")
public class FishingWebProperties {
    private boolean enabled = false;
    private String adminUsername = "admin";
    private String adminPasswordHash = "";
    private int sessionMinutes = 30;
}
