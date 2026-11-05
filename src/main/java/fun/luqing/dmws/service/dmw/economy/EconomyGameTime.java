package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.config.EconomyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class EconomyGameTime {
    private final EconomyProperties properties;
    public LocalDateTime now() { return LocalDateTime.now(properties.zoneId()); }
    public LocalDate today() { return now().toLocalDate(); }
}
