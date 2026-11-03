package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.config.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class HelpPlugin {

    private final ConfigManager configManager;
    private final ApplicationContext applicationContext; //注入Spring上下文

    @AnyMessageHandler
    @MessageHandlerFilter(cmd = "指令列表")
    @Async("taskExecutor")
    @CommandInfo(cmd = "指令列表", desc = "获取指令列表")
    public void helpList(Bot bot, AnyMessageEvent event) {
        List<String> messageList = new ArrayList<>();

        // ✅ 从Spring容器中获取所有Bean
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean); // 处理AOP代理类

            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(CommandInfo.class)) {
                    CommandInfo info = method.getAnnotation(CommandInfo.class);

                    // 获取指令名
                    String cmdName = !info.cmd().isEmpty() ? info.cmd() : info.startWith();
                    String desc = info.desc();

                    messageList.add(cmdName + " - " + desc);
                }
            }
        }

        if (messageList.isEmpty()) {
            messageList.add("未检测到任何指令。请确认插件中存在 @CommandInfo 注解的方法。");
        }

        List<Map<String, Object>> forwardMsg = ShiroUtils.generateForwardMsg(
                configManager.getDmwConfig().getBotId(),
                configManager.getDmwConfig().getBotName(),
                messageList
        );

        bot.sendForwardMsg(event, forwardMsg);
    }

    // ✅ 静态方法改为扫描整个上下文（如果你需要在别处用）
    public boolean isCommandExist(String input) {
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);

            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(CommandInfo.class)) {
                    CommandInfo info = method.getAnnotation(CommandInfo.class);

                    if (!info.cmd().isEmpty() && input.equals(info.cmd())) {
                        return true;
                    }
                    if (!info.startWith().isEmpty() && input.startsWith(info.startWith())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
