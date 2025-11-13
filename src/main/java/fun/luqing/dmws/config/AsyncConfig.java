package fun.luqing.dmws.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置类
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 配置异步任务线程池
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：线程池维护线程的最少数量
        executor.setCorePoolSize(10);

        // 最大线程数：线程池维护线程的最大数量
        executor.setMaxPoolSize(50);

        // 队列容量：用于缓冲任务的队列大小
        executor.setQueueCapacity(200);

        // 线程池名的前缀，便于调试和监控
        executor.setThreadNamePrefix("dmw-async-");

        // 线程空闲后的存活时长（秒）
        executor.setKeepAliveSeconds(120);

        // 拒绝策略：当线程池和队列都满时调用
        // CallerRunsPolicy：由调用线程（提交任务的线程）处理该任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 线程池关闭时等待所有任务完成
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待终止的超时时间（秒）
        executor.setAwaitTerminationSeconds(60);

        // 初始化线程池
        executor.initialize();

        return executor;
    }
}