package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.tomatoservice.TomatoBookContentService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookListService;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import fun.luqing.dmws.common.annotation.CommandInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateChapterCommand {

    private final TomatoBookContentService contentService;
    private final TomatoBookListService bookListService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final ConfigManager configManager;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "更新章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "更新章节内容", desc = "更新数据库中指定书籍的所有章节内容")
    public void updateAllChapterContents(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();

        if (!isPrivileged(bot, event)) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "该指令仅限主人或本群管理员使用。");
            return;
        }

        String param = event.getMessage().replace("更新章节内容", "").trim();

        if (param.isEmpty()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请输入订阅ID，例如：更新章节内容 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式错误，请输入数字ID。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = subscriptionService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();
        TomatoBookList book = bookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }

        TomatoBookContentService.UpdateResult updateResult = contentService.updateAllChapters(bookId,false);
        String resultMsg = String.format("《%s》章节内容更新完成。\n成功：%d 章\n失败：%d 章",
                book.getBookName(), updateResult.success(), updateResult.fail());
        TomatoMessageUtil.sendGroupMessage(bot, groupId, resultMsg);
    }

    /**
     * 判断操作者是否为主人、群主或管理员
     */
    private boolean isPrivileged(Bot bot, GroupMessageEvent event) {
        long userId = event.getUserId();
        if (configManager.getDmwConfig().isMaster(userId)) {
            return true;
        }
        String role = event.getSender() == null ? null : event.getSender().getRole();
        if ("owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role)) {
            return true;
        }
        try {
            GroupMemberInfoResp member = bot.getGroupMemberInfo(event.getGroupId(), userId, false).getData();
            if (member == null) return false;
            String memberRole = member.getRole();
            return "owner".equalsIgnoreCase(memberRole) || "admin".equalsIgnoreCase(memberRole);
        } catch (Exception e) {
            log.warn("获取群成员信息失败: {}", e.getMessage());
            return false;
        }
    }
}
