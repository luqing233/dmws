package fun.luqing.dmws.service.dmw.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Display-only resolution. Never creates/updates a profile or falls back to a QQ number. */
@Service
@RequiredArgsConstructor
public class EconomyPlayerNameService {
    private final DmwConfig config;
    private final BotContainer bots;
    private final EconomyUserProfileRepository profiles;

    public String resolve(Long groupId, long userId) {
        Bot bot = config.getBotId() > 0 ? bots.robots.get(config.getBotId())
                : bots.robots.size() == 1 ? bots.robots.values().iterator().next() : null;
        return resolve(bot, groupId, userId);
    }

    public String resolve(Bot bot, Long groupId, long userId) {
        String name = online(bot, groupId, userId);
        return name.isBlank() ? stored(userId) : name;
    }

    public String member(Bot bot, long groupId, GroupMemberInfoResp member) {
        String name = visible(member.getCard(), member.getNickname(), member.getUserId());
        return name.isBlank() ? resolve(bot, groupId, member.getUserId()) : name;
    }

    public Map<Long, String> resolveAll(Bot bot, long groupId, Collection<Long> users) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (bot != null) {
            try {
                var response = bot.getGroupMemberList(groupId);
                if (response != null && response.getData() != null) {
                    for (var member : response.getData()) {
                        if (member.getUserId() != null && users.contains(member.getUserId())) {
                            String name = visible(member.getCard(), member.getNickname(), member.getUserId());
                            if (!name.isBlank()) names.put(member.getUserId(), name);
                        }
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        for (long user : users) names.computeIfAbsent(user, id -> resolve(bot, groupId, id));
        return names;
    }

    private String online(Bot bot, Long groupId, long userId) {
        if (bot == null) return "";
        if (groupId != null && groupId > 0) {
            try {
                var response = bot.getGroupMemberInfo(groupId, userId, false);
                if (response != null && response.getData() != null) {
                    var member = response.getData();
                    String name = visible(member.getCard(), member.getNickname(), userId);
                    if (!name.isBlank()) return name;
                }
            } catch (RuntimeException ignored) { }
        }
        try {
            var response = bot.getStrangerInfo(userId, false);
            if (response != null && response.getData() != null) return usable(response.getData().getNickname(), userId);
        } catch (RuntimeException ignored) { }
        return "";
    }

    private String stored(long userId) {
        try {
            String name = profiles.findById(userId).map(profile -> usable(profile.getNickname(), userId)).orElse("");
            if (!name.isBlank()) return name;
        } catch (RuntimeException ignored) { }
        return "这位玩家";
    }

    public static String visible(String card, String nickname, long userId) {
        String name = usable(card, userId);
        return name.isBlank() ? usable(nickname, userId) : name;
    }

    public static String usable(String name, long userId) {
        if (name == null || name.isBlank() || name.trim().equals(Long.toString(userId))) return "";
        return name.trim();
    }
}
