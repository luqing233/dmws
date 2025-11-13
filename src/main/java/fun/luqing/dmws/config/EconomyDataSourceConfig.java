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
 * Economy 数据源配置，绑定 fun.luqing.dmws.repository.economy 下的所有 Repository 到 economy 数据源
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "fun.luqing.dmws.repository.economy", // 指定 economy 包下的所有 Repository
        entityManagerFactoryRef = "economyEntityManagerFactory",
        transactionManagerRef = "economyTransactionManager"
)
public class EconomyDataSourceConfig {

    /**
     * 配置 economy 数据源属性
     */
    @Bean(name = "economyDataSourceProperties")
    @ConfigurationProperties("spring.datasource.economy")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 创建 economy 数据源
     */
    @Bean(name = "economyDataSource")
    public DataSource dataSource(@Qualifier("economyDataSourceProperties") DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * 配置   economy 实体管理器工厂
     */
    @Bean(name = "economyEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("economyDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("fun.luqing.dmws.entity.economy") // 新实体包
                .persistenceUnit("economy")
                .properties(Map.of(
                        //"hibernate.hbm2ddl.auto", "validate",
                        "hibernate.format_sql", "false"
                        //"hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect" // 显式指定 MySQL 方言
                ))
                .build();
    }

    /**
     * 配置 economy 事务管理器
     */
    @Bean(name = "economyTransactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("economyEntityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory.getObject());
    }
}