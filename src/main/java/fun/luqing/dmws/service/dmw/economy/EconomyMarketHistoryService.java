package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketQuote;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.repository.dmw.economy.EconomyMarketQuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EconomyMarketHistoryService {
    private final EconomyMarketQuoteRepository quotes;
    private final EconomyGameTime time;

    public enum Period {
        DAY(1, 10, "24小时"), WEEK(7, 60, "7天"), MONTH(30, 240, "30天");
        public final int days, minutes;
        public final String label;
        Period(int days, int minutes, String label) { this.days=days; this.minutes=minutes; this.label=label; }
        public static Period parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "24小时", "24h", "1天" -> DAY;
                case "7天", "7d" -> WEEK;
                case "30天", "30d" -> MONTH;
                default -> throw new EconomyException("INVALID_PERIOD", "查询范围支持24小时、7天或30天");
            };
        }
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager", isolation = Isolation.REPEATABLE_READ)
    public History query(EconomyFxPair pair, Period period) {
        if (pair == null || period == null) throw new EconomyException("INVALID_HISTORY", "请选择货币对和查询范围");
        LocalDateTime end = time.now(), start = end.minusDays(period.days);
        return summarize(pair, period, start, end,
                quotes.findByQuotedAtGreaterThanEqualAndQuotedAtLessThanEqualOrderByQuotedAtAsc(start, end));
    }

    /** Observed OHLC only; a missing ten-minute snapshot starts a fresh candle even inside a bucket. */
    public static History summarize(EconomyFxPair pair, Period period, LocalDateTime start, LocalDateTime end, List<EconomyMarketQuote> rows) {
        List<Sample> raw = rows.stream().filter(r -> !r.getQuotedAt().isBefore(start) && !r.getQuotedAt().isAfter(end))
                .sorted(Comparator.comparing(EconomyMarketQuote::getQuotedAt))
                .map(r -> new Sample(r.getQuotedAt(), r.price(pair), 0)).toList();
        List<Candle> chart = new ArrayList<>();
        int segment = 0, gaps = 0;
        Sample previous = null;
        LocalDateTime bucket = null;
        for (var row : raw) {
            boolean gap = previous != null && row.at().isAfter(previous.at().plusMinutes(10));
            if (gap) { segment++; gaps++; }
            LocalDateTime currentBucket = row.at().truncatedTo(ChronoUnit.DAYS)
                    .plusMinutes((row.at().getHour() * 60 + row.at().getMinute()) / period.minutes * (long)period.minutes);
            if (!gap && currentBucket.equals(bucket)) {
                Candle candle = chart.getLast();
                chart.set(chart.size()-1, new Candle(row.at(), candle.open(), candle.high().max(row.price()),
                        candle.low().min(row.price()), row.price(), segment));
            } else {
                // Adjacent candles share the last observed close. Never carry an opening price across a gap.
                BigDecimal open = previous == null || gap ? row.price() : previous.price();
                chart.add(new Candle(row.at(), open, open.max(row.price()), open.min(row.price()), row.price(), segment));
            }
            previous = row; bucket = currentBucket;
        }
        LocalDateTime firstExpected = EconomyMarketService.quoteSlot(start);
        if (firstExpected.isBefore(start)) firstExpected = firstExpected.plusMinutes(10);
        LocalDateTime lastExpected = EconomyMarketService.quoteSlot(end);
        boolean partial = raw.isEmpty() || raw.getFirst().at().isAfter(firstExpected)
                || raw.getLast().at().isBefore(lastExpected) || gaps > 0;
        if (raw.isEmpty()) return new History(pair,period,start,end,null,null,null,null,null,null,null,0,0,true,List.of(),List.of());
        BigDecimal first = raw.getFirst().price(), last = raw.getLast().price();
        BigDecimal low = raw.stream().map(Sample::price).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal high = raw.stream().map(Sample::price).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal change = last.subtract(first).divide(first,12,RoundingMode.HALF_UP);
        List<Sample> recent = new ArrayList<>(raw.subList(Math.max(0,raw.size()-11),raw.size()));
        return new History(pair,period,start,end,raw.getFirst().at(),raw.getLast().at(),first,last,low,high,change,
                raw.size(),gaps,partial,List.copyOf(chart),List.copyOf(recent));
    }

    public record Sample(LocalDateTime at, BigDecimal price, int segment) { }
    public record Candle(LocalDateTime at, BigDecimal open, BigDecimal high, BigDecimal low,
                         BigDecimal close, int segment) { }
    /** recent includes a predecessor so the last ten rows can show real per-round changes. */
    public record History(EconomyFxPair pair, Period period, LocalDateTime start, LocalDateTime end,
                          LocalDateTime firstAt, LocalDateTime lastAt, BigDecimal first, BigDecimal last,
                          BigDecimal low, BigDecimal high, BigDecimal change, int count, int gaps,
                          boolean partial, List<Candle> chart, List<Sample> recent) { }
}
