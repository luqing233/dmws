package fun.luqing.dmws.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

/**
 * HyEconomy 数据源配置，绑定 fun.luqing.dmws.repository.hyeconomy 下的所有 Repository 到 hy-economy 数据源
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "fun.luqing.dmws.repository.hyeconomy", // 指定 hyeconomy 包下的所有 Repository
        entityManagerFactoryRef = "hyEconomyEntityManagerFactory",
        transactionManagerRef = "hyEconomyTransactionManager"
)
public class HyEconomyDataSourceConfig {

    /**
     * 配置 hy-economy 数据源属性
     */
    @Bean(name = "hyEconomyDataSourceProperties")
    @ConfigurationProperties("spring.datasource.hy-economy")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 创建 hy-economy 数据源
     */
    @Bean(name = "hyEconomyDataSource")
    public DataSource dataSource(@Qualifier("hyEconomyDataSourceProperties") DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * 配置 hy-economy 实体管理器工厂
     */
    @Bean(name = "hyEconomyEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("hyEconomyDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("fun.luqing.dmws.entity.hy_economy") // 新实体包
                .persistenceUnit("hy_economy")
                .properties(Map.of(
                        //"hibernate.hbm2ddl.auto", "validate",
                        "hibernate.format_sql", "false"
                        //"hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect" // 显式指定 MySQL 方言
                ))
                .build();
    }

    /**
     * 配置 hy-economy 事务管理器
     */
    @Bean(name = "hyEconomyTransactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("hyEconomyEntityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory.getObject());
    }
}