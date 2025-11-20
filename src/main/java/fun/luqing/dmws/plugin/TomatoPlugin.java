package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.client.TomatoUpdateRecord;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.client.TomatoContentRecord;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.entity.dmw.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;

import fun.luqing.dmws.service.TomatoBookContentService;
import fun.luqing.dmws.service.TomatoBookListService;
import fun.luqing.dmws.service.TomatoSubscriptionGroupService;
import fun.luqing.dmws.service.TomatoBookChartService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.mikuac.shiro.common.utils.ShiroUtils.generateForwardMsg;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class TomatoPlugin {

    @Resource
    private BotContainer botContainer;

    private final ConfigManager configManager;

    private final TomatoBookListService tomatoBookListService;

    private final TomatoBookContentService tomatoBookContentService;

    private final TomatoSubscriptionGroupService tomatoSubscriptionGroupService;

    private final TomatoBookChartService tomatoBookChartService;

    private final TomatoApiClient tomatoApiClient;



    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "番茄订阅")
    @Async("taskExecutor")
    @CommandInfo(startWith = "番茄订阅", desc = "订阅指定id的书籍，注意，此处的id为bookId，可以使用番茄的分享功能分享链接获取")
    public void subscribeBook(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String raw = event.getMessage().trim();
        boolean atAll = raw.contains("全体") || raw.contains("all");

        String bookId = raw.replace("番茄订阅", "").replace("全体", "").replace("all", "").trim();
        if (bookId.isEmpty()) {
            sendGroupMessage(bot, groupId, "请指定书籍ID，例如：番茄订阅 123456");
            return;
        }
        log.info("[番茄订阅] 群:{} 用户:{} 请求订阅 bookId={}", groupId, event.getUserId(), bookId);

        // 查询书籍

        TomatoBookList book = tomatoBookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            log.info("[番茄订阅] bookId={} 本地未记录，开始从API初始化书籍信息…", bookId);

            TomatoUpdateRecord rec = tomatoApiClient.getBookDetail1(bookId);
            if (rec == null) {
                sendGroupMessage(bot, groupId, "无法获取书籍信息，请检查ID是否正确。");
                return;
            }

            // 初始化书籍信息
            book = new TomatoBookList();
            book.setBookId(bookId);
            book.setBookName(rec.bookName());
            book.setLastTitle(rec.lastChapterTitle());
            book.setLastChapterId(rec.lastChapterId());
            book.setLastTime(rec.lastPublishTime());
            book.setTotalWords(rec.totalWords());
            tomatoBookListService.addBook(book);

            // 初始化章节目录（不含正文）
            TomatoBookContentService.UpdateResult updateResult= tomatoBookContentService.updateChapterList(bookId);


            log.info("[番茄订阅] 《{}》章节初始化完成，共 {} 章，成功 {} 章，失败 {} 章", book.getBookName(), updateResult.total(),updateResult.total(),updateResult.fail());

            sendGroupMessage(bot, groupId, "已初始化《" + book.getBookName() + "》章节目录，共 " + updateResult.total() + " 章。");
        }

        // 查询订阅
        if (tomatoSubscriptionGroupService.findByGroupIdAndBookId(groupId, bookId).isPresent()) {
            log.info("[番茄订阅] 群:{} 已订阅 bookId={}，忽略请求", groupId, bookId);
            sendGroupMessage(bot, groupId, "该书已订阅，无需重复。");
            return;
        }

        // 新增订阅
        TomatoSubscriptionGroup sub = new TomatoSubscriptionGroup();
        sub.setGroupId(groupId);
        sub.setBookId(bookId);
        sub.setOperator(event.getUserId());
        sub.setAtAll(atAll);
        sub.setEnable(true);

        tomatoSubscriptionGroupService.addSubscription(sub);

        log.info("[番茄订阅] 群:{} 成功订阅 《{}》({})", groupId, book.getBookName(), bookId);
        sendGroupMessage(bot, groupId,
                "订阅成功：\n《" + book.getBookName() + "》\n最新章节：" + book.getLastTitle() + "\n更新时间：" + book.getLastTime());
    }


    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取目录")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取目录", desc = "查看指定订阅书籍的目录，例如：获取目录 1，此处 id 需要通过 订阅列表 功能获取")
    public void getChapterList(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("获取目录", "").trim();


        if (param.isEmpty()) {
            sendGroupMessage(bot, groupId, "请输入订阅序号，例如：获取目录 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            sendGroupMessage(bot, groupId, "格式错误，请输入数字序号。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = tomatoSubscriptionGroupService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();
        TomatoBookList book = tomatoBookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }


        log.info("[获取目录] 群:{} 用户:{} 请求 subId={}", groupId, event.getUserId(), subId);

        List<TomatoBookContent> chapters =tomatoBookContentService.getChapterList(bookId);

        if (chapters == null){
            sendGroupMessage(bot, groupId, "获取目录失败");
            return;
        }
        // 构建 forwardMsg
        List<String> messageList = new ArrayList<>();
        messageList.add("《" + book.getBookName() + "》目录：");
        for (TomatoBookContent c : chapters) {
            messageList.add(c.getRealChapterOrder() + ".   " + c.getTitle());
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                1945927750L,
                "猫猫02号机",
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }



    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "更新章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "更新章节内容", desc = "更新数据库中指定书籍的所有章节内容，例如：更新章节内容 1（订阅ID）")
    public void updateAllChapterContents(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("更新章节内容", "").trim();

        if (param.isEmpty()) {
            sendGroupMessage(bot, groupId, "请输入订阅ID，例如：更新章节内容 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            sendGroupMessage(bot, groupId, "格式错误，请输入数字ID。");
            return;
        }
        Optional<TomatoSubscriptionGroup> subOpt = tomatoSubscriptionGroupService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();

        TomatoBookList book = tomatoBookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }


        log.info("[更新章节内容] 群:{} 用户:{} 请求更新 subId={} (bookId={})",
                groupId, event.getUserId(), subId, bookId);


        sendGroupMessage(bot, groupId, "开始更新《" + book.getBookName() + "》的章节内容，请稍候……");

        TomatoBookContentService.UpdateResult updateResult= tomatoBookContentService.updateAllChapters(bookId,false);

        log.info("[更新章节内容] 《{}》 更新完成：成功 {}，失败 {}", book.getBookName(), updateResult.success(), updateResult.fail());
        String resultMsg = String.format("《%s》章节内容更新完成。\n成功：%d 章\n失败：%d 章",
                book.getBookName(), updateResult.success(), updateResult.fail());


        sendGroupMessage(bot, groupId, resultMsg);
    }


    /**
     * 查看订阅列表（合并转发格式）
     */
    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "订阅列表")
    @Async("taskExecutor")
    @CommandInfo(cmd = "订阅列表", desc = "查看当前群的番茄小说订阅列表")
    public void selectAllBook(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        List<TomatoSubscriptionGroup> subscriptions = tomatoSubscriptionGroupService.findByGroupId(groupId);
        if (subscriptions.isEmpty()) {
            log.info("[订阅列表] 群:{} 暂无订阅记录", groupId);
            sendGroupMessage(bot, groupId, "本群暂无订阅的书籍哦~");
            return;
        }
        log.info("[订阅列表] 群:{} 用户:{} 请求查看订阅列表", groupId, event.getUserId());


        List<String> messageList = new ArrayList<>();
        messageList.add("📚 本群订阅列表：");

        for (TomatoSubscriptionGroup sub : subscriptions) {
            String bookName = tomatoBookListService.findByBookId(sub.getBookId())
                    .map(TomatoBookList::getBookName).orElse("[未知书名]");

            messageList.add("ID: " + sub.getId());
            messageList.add("《" + bookName + "》");
            messageList.add("bookId: " + sub.getBookId() + (sub.isAtAll() ? "（@全体）" : ""));
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                1945927750L,
                "猫猫02号机",
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取章节内容", desc = "根据订阅ID和章节序号获取章节内容，例如：获取章节内容 1 10，此处两个值分别通过 订阅列表 指令和 获取目录 指令获取")
    public void getChapterContent(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String[] params = event.getMessage().replace("获取章节内容", "").trim().split("\\s+");
        if (params.length < 2) {
            sendGroupMessage(bot, groupId, "请输入订阅ID和章节序号，例如：获取章节内容 1 10");
            return;
        }

        long subId;
        int chapterIndex;
        try {
            subId = Long.parseLong(params[0]);
            chapterIndex = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            sendGroupMessage(bot, groupId, "格式错误，请输入数字ID和章节号。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = tomatoSubscriptionGroupService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();
        TomatoBookList book = tomatoBookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }

        List<TomatoBookContent> chapters = tomatoBookContentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
        if (chapterIndex < 1 || chapterIndex > chapters.size()) {
            sendGroupMessage(bot, groupId, "章节序号超出范围。");
            return;
        }

        TomatoBookContent chapter = chapters.get(chapterIndex - 1);


        log.info("[获取章节内容] 群:{} 用户:{} subId={} chapterIndex={}",
                groupId, event.getUserId(), subId, chapterIndex);

        if (chapter.getContent() == null) {

            log.info("[获取章节内容] 本地无正文，正在从API获取内容 chapterId={}", chapter.getChapterId());

            fun.luqing.dmws.client.TomatoContentRecord recode = tomatoApiClient.getChapterContent(chapter.getChapterId());
            if (recode != null) {
                chapter.setContent(recode.content());
                chapter.setWordCount(recode.wordCount());
                tomatoBookContentService.saveOrUpdate(chapter);
            } else {
                sendGroupMessage(bot, groupId, "无法获取章节内容，请稍后再试。");
                return;
            }
        }

        Document doc = Jsoup.parse(chapter.getContent());

        // 优先获取 h1 作为标题
        Elements h1s = doc.select("h1");
        String header = chapter.getTitle();
        if (!h1s.isEmpty()) header = h1s.first().text();

        // 先查 article p，如果为空则查 body > p
        Elements paragraphs = doc.select("article p");
        if (paragraphs.isEmpty()) paragraphs = doc.select("body > p");

        List<String> messageList = new ArrayList<>();
        messageList.add("《" + book.getBookName() + "》");
        messageList.add(header);
        messageList.add("共 " + chapter.getWordCount() + " 字");
        for (Element p : paragraphs) {
            String text = p.text();
            if (!text.isEmpty()) messageList.add(text);
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                1945927750L,
                "猫猫02号机",
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }


    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取更新图表")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取更新图表", desc = "根据订阅ID获取书籍最近30天更新图表，例如：获取更新图表 1，如遇异常书籍。使用 更新章节内容 指令刷新后重试")
    public void getBookUpdateChart(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("获取更新图表", "").trim();

        if (param.isEmpty()) {
            sendGroupMessage(bot, groupId, "请输入订阅序号，例如：获取更新图表 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            sendGroupMessage(bot, groupId, "格式错误，请输入数字序号。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = tomatoSubscriptionGroupService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();

        TomatoBookList book = tomatoBookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }
        log.info("[获取更新图表] 群:{} 用户:{} 请求 subId={}",
                groupId, event.getUserId(), subId);


        String chartUrl;
        try {
            log.info("正在更新章节内容");
            tomatoBookContentService.updateAllChapters(bookId,false);
            chartUrl = tomatoBookChartService.generateBook30DaysChart(bookId,book.getBookName());
        } catch (Exception e) {
            sendGroupMessage(bot, groupId, "生成图表失败：" + e.getMessage());
            return;
        }
        log.info("[获取更新图表] 《{}》 图表生成成功：{}", book.getBookName(), chartUrl);


        bot.sendGroupMsg(groupId, MsgUtils.builder().text("《"+book.getBookName()+"》最近30天更新情况为\n").img(chartUrl).build(), false);


    }



    /**
     * 取消订阅
     */
    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "取消订阅")
    @Async("taskExecutor")
    @CommandInfo(startWith = "取消订阅", desc = "取消订阅指定ID的记录，例如：取消订阅 1，此处id需要通过 订阅列表 功能获取")
    public void unsubscribeById(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("取消订阅\\s*(\\d+)").matcher(event.getMessage());
        if (!matcher.find()) {
            sendGroupMessage(bot, groupId, "请使用：取消订阅 <ID>，例如：取消订阅 1");
            return;
        }


        try {
            Long id = Long.parseLong(matcher.group(1));
            log.info("[取消订阅] 群:{} 用户:{} 请求取消 subId={}", groupId, event.getUserId(), id);

            Optional<TomatoSubscriptionGroup> optional = tomatoSubscriptionGroupService.findById(id);
            if (optional.isEmpty()) {
                sendGroupMessage(bot, groupId, "未找到 ID 为 " + id + " 的订阅。");
                return;
            }

            TomatoSubscriptionGroup sub = optional.get();
            if (sub.getGroupId() != groupId) {
                sendGroupMessage(bot, groupId, "该订阅不属于本群，无法删除。");
                return;
            }

            tomatoSubscriptionGroupService.deleteById(id);
            log.info("[取消订阅] 群:{} 已取消订阅 bookId={}", groupId, sub.getBookId());

            sendGroupMessage(bot, groupId, "已取消订阅：" + sub.getBookId());

        } catch (Exception e) {
            log.error("取消订阅异常: {}", e.getMessage());
            sendGroupMessage(bot, groupId, "删除订阅失败：" + e.getMessage());
        }
    }


    /**
     * 定时检查更新
     */
    @Scheduled(fixedDelay = 1000 * 60 * 4)
    public void updateSubscribedBooks() {
        try {
            //log.info("开始更新书库内容");
            List<TomatoBookList> books = tomatoBookListService.findAll();
            if (books.isEmpty()) return;

            Bot bot = botContainer.robots.get(configManager.getDmwConfig().getBot_id());
            if (bot == null) return;

            for (TomatoBookList book : books) {
                try {
                    int delay = 10 + new Random().nextInt(16); // 10~25 秒
                    //log.info("等待 {} 秒后检查《{}》是否有更新...", delay, book.getBookName());
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                fun.luqing.dmws.client.TomatoUpdateRecord info = tomatoApiClient.getBookDetail(book.getBookId());
                if (info == null) continue;

                if (!Objects.equals(book.getLastChapterId(), info.lastChapterId())) {

                    book.setLastChapterId(info.lastChapterId());
                    book.setLastTitle(info.lastChapterTitle());
                    book.setLastTime(info.lastPublishTime());
                    book.setTotalWords(info.totalWords());
                    tomatoBookListService.saveOrUpdate(book);

                    log.info("[定时更新] 《{}》 发现新章节：{}({})",
                            book.getBookName(), info.lastChapterTitle(), info.lastChapterId());


                    //保存新章节
                    TomatoContentRecord recode = tomatoApiClient.getChapterContent(info.lastChapterId());
                    if (recode != null) {
                        TomatoBookContent chapter = new TomatoBookContent(
                                null,
                                recode.bookId(),
                                info.lastChapterId(),
                                recode.realChapterOrder(),
                                recode.title(),
                                recode.content(),
                                recode.updateTime(),
                                recode.wordCount()
                        );
                        tomatoBookContentService.saveOrUpdate(chapter);

                    }
                    notifyGroups(book, bot);
                }
            }
            //log.info("书库更新结束");
        } catch (Exception e) {
            log.error("更新任务异常: {}", e.getMessage());
        }
    }


    /**
     * 群消息发送
     */
    private void sendGroupMessage(Bot bot, long groupId, String message) {
        try {
            bot.sendGroupMsg(groupId, message, false);
        } catch (Exception e) {
            log.error("发送群消息失败: {}", e.getMessage());
        }
    }

    /**
     * 通知订阅群
     */
    private void notifyGroups(TomatoBookList book, Bot bot) {
        List<TomatoSubscriptionGroup> groups = tomatoSubscriptionGroupService.findByBookId(book.getBookId());
        String msg = "📢 《" + book.getBookName() + "》更新啦！\n最新章节：" + book.getLastTitle() + "\n更新时间：" + book.getLastTime();
        log.info("[定时更新] 开始群通知：《{}》更新", book.getBookName());
        for (TomatoSubscriptionGroup g : groups) {
            sendGroupMessage(bot, g.getGroupId(), msg);
        }
    }

}
