package fun.luqing.dmws;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class LegacyMappingRetirementTests {
    @PersistenceContext(unitName = "dmw")
    private EntityManager entityManager;

    @Test
    void retiresUnusedGameTablesButKeepsActiveFishAndEconomyMappings() {
        Set<String> tables = entityManager.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType().getAnnotation(Table.class))
                .filter(java.util.Objects::nonNull)
                .map(Table::name).collect(Collectors.toSet());
        for (String legacy : Set.of("user_info", "user_backpack", "user_sign_in_history",
                "user_status", "item_data", "hospitalization_history")) {
            assertFalse(tables.contains(legacy), legacy + " must not be recreated by Hibernate");
        }
        assertTrue(tables.containsAll(Set.of("fish", "economy_account", "economy_user_profile",
                "economy_user_item", "tomato_book_content")));
    }
}
