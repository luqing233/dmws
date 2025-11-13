package fun.luqing.dmws.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

/**
 * DMW 数据源配置，绑定 fun.luqing.dmws.repository.dmw 下的所有 Repository 到 dmw 数据源
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "fun.luqing.dmws.repository.dmw", // 指定 dmw 包下的所有 Repository
        entityManagerFactoryRef = "dmwEntityManagerFactory",
        transactionManagerRef = "dmwTransactionManager"
)
public class DmwDataSourceConfig {

    /**
     * 配置 dmw 数据源属性
     */
    @Primary
    @Bean(name = "dmwDataSourceProperties")
    @ConfigurationProperties("spring.datasource.dmw")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 创建 dmw 数据源
     */
    @Primary
    @Bean(name = "dmwDataSource")
    public DataSource dataSource(@Qualifier("dmwDataSourceProperties") DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * 配置 dmw 实体管理器工厂
     */
    @Primary
    @Bean(name = "dmwEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("dmwDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("fun.luqing.dmws.entity.dmw") // 现有实体包
                .persistenceUnit("dmw")
                .properties(Map.of(
                        //"hibernate.hbm2ddl.auto", "validate",
                        "hibernate.format_sql", "false"
                        // 移除 "hibernate.dialect", "org.hibernate.dialect.MySQL8Dialect"
                ))
                .build();
    }

    /**
     * 配置 dmw 事务管理器
     */
    @Primary
    @Bean(name = "dmwTransactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("dmwEntityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory.getObject());
    }
}