package fun.luqing.dmws.repository.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.time.LocalDateTime;

public interface EconomyMarketQuoteRepository extends JpaRepository<EconomyMarketQuote, Long> {
    List<EconomyMarketQuote> findAllByOrderByIdDesc(Pageable page);
    List<EconomyMarketQuote> findByQuotedAtGreaterThanEqualAndQuotedAtLessThanEqualOrderByQuotedAtAsc(LocalDateTime start, LocalDateTime end);
}
