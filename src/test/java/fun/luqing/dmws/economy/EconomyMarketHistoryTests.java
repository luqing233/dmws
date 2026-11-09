package fun.luqing.dmws.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyMarketQuote;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.repository.dmw.economy.EconomyMarketQuoteRepository;
import fun.luqing.dmws.service.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyMarketHistoryTests {
    static final LocalDateTime END=LocalDateTime.of(2026,11,9,12,0);
    static EconomyMarketQuote row(LocalDateTime at,String price) {
        var row=new EconomyMarketQuote();row.setQuotedAt(at);row.price(EconomyFxPair.EUR_USD,new BigDecimal(price));return row;
    }
    @Test void readsOnlyTheRequestedRangeAndDoesNotGenerateQuotes() {
        var repo=mock(EconomyMarketQuoteRepository.class);var time=mock(EconomyGameTime.class);when(time.now()).thenReturn(END);
        when(repo.findByQuotedAtGreaterThanEqualAndQuotedAtLessThanEqualOrderByQuotedAtAsc(END.minusDays(7),END)).thenReturn(List.of(row(END,"1.08")));
        var view=new EconomyMarketHistoryService(repo,time).query(EconomyFxPair.EUR_USD,Period.WEEK);
        assertEquals(1,view.count());assertTrue(view.partial());
        verify(repo).findByQuotedAtGreaterThanEqualAndQuotedAtLessThanEqualOrderByQuotedAtAsc(END.minusDays(7),END);verifyNoMoreInteractions(repo);
    }
    @Test void aggregationKeepsRawExtremaAndDoesNotBridgeGapsInsideTheSameBucket() {
        var start=END.minusHours(1);
        var rows=List.of(row(start,"1.08"),row(start.plusMinutes(10),"1.10"),row(start.plusMinutes(20),"1.09"),row(start.plusMinutes(40),"1.07"),row(start.plusMinutes(50),"1.08"));
        var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.WEEK,start,END,rows);
        assertEquals(5,view.count());assertEquals(new BigDecimal("1.10"),view.high());assertEquals(new BigDecimal("1.07"),view.low());
        assertEquals(2,view.chart().size());assertNotEquals(view.chart().getFirst().segment(),view.chart().getLast().segment());
        assertEquals(start.plusMinutes(20),view.chart().getFirst().at());assertEquals(start.plusMinutes(50),view.chart().getLast().at());
        assertCandle(view.chart().getFirst(),"1.08","1.10","1.08","1.09");
        assertCandle(view.chart().getLast(),"1.07","1.08","1.07","1.08");
        assertEquals(1,view.gaps());assertTrue(view.partial());
        assertTrue(EconomyMarketText.recentPrices(view).contains("前轮缺失"));
    }
    @Test void candlesUseObservedEndpointsAndNeverCarryTheOpenAcrossAGap() {
        var start=END.minusHours(1);
        var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.DAY,start,END,
                List.of(row(start,"1.08"),row(start.plusMinutes(10),"1.10"),row(start.plusMinutes(20),"1.09"),row(start.plusMinutes(40),"1.07")));
        assertCandle(view.chart().get(0),"1.08","1.08","1.08","1.08");
        assertCandle(view.chart().get(1),"1.08","1.10","1.08","1.10");
        assertCandle(view.chart().get(2),"1.10","1.10","1.09","1.09");
        assertCandle(view.chart().get(3),"1.07","1.07","1.07","1.07");
        // A continuous bucket opens at the prior observed close, and includes that endpoint in its extrema.
        var weekly=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.WEEK,start,END,
                List.of(row(END.minusMinutes(10),"1.09"),row(END,"1.08")));
        assertEquals(2,weekly.chart().size());
        assertCandle(weekly.chart().getLast(),"1.09","1.09","1.08","1.08");
    }
    private static void assertCandle(Candle candle,String open,String high,String low,String close) {
        assertEquals(new BigDecimal(open),candle.open());assertEquals(new BigDecimal(high),candle.high());
        assertEquals(new BigDecimal(low),candle.low());assertEquals(new BigDecimal(close),candle.close());
    }
    @Test void allRangesPreserveRawDataAndUseTheirSpecifiedGranularity() {
        for(var period:Period.values()) {
            var start=END.minusDays(period.days);List<EconomyMarketQuote> rows=new ArrayList<>();
            for(var t=start;!t.isAfter(END);t=t.plusMinutes(10))rows.add(row(t,"1.08"));
            var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,period,start,END,rows);
            assertFalse(view.partial());assertEquals(0,view.gaps());assertEquals(rows.size(),view.count());
            assertTrue(view.chart().size()<=period.days*24*60/period.minutes+1);
            assertEquals(BigDecimal.ZERO,view.change().stripTrailingZeros());assertEquals(11,view.recent().size());
            assertEquals(11,EconomyMarketText.recentPrices(view).lines().count());
        }
        assertThrows(fun.luqing.dmws.common.economy.EconomyException.class,()->Period.parse("365天"));
    }
    @Test void missingBoundarySnapshotsArePartialButUnalignedWindowEdgesAreNotGaps() {
        var start=END.minusHours(1);
        List<EconomyMarketQuote> rows=new ArrayList<>();
        for(var t=start;!t.isAfter(END);t=t.plusMinutes(10))rows.add(row(t,"1.08"));
        for(var period:Period.values()) {
            assertTrue(EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,period,start,END,rows.subList(1,rows.size())).partial());
            assertTrue(EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,period,start,END,rows.subList(0,rows.size()-1)).partial());
            assertFalse(EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,period,start.plusMinutes(1),END.plusMinutes(9),rows.subList(1,rows.size())).partial());
        }
    }
    @Test void emptySingleAndFlatChartsAreValidAndOutsideRowsAreExcluded() throws Exception {
        var renderer=new EconomyMarketChartService();
        for(var rows:List.of(List.<EconomyMarketQuote>of(),List.of(row(END,"1.08")),List.of(row(END.minusMinutes(10),"1.08"),row(END,"1.08")))) {
            var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.DAY,END.minusDays(1),END,rows);
            var image=ImageIO.read(new ByteArrayInputStream(renderer.render(view)));assertNotNull(image);assertEquals(1280,image.getWidth());assertEquals(760,image.getHeight());
            assertTrue(view.count()==0 ? EconomyMarketText.priceHistory(view).contains("暂无历史快照") : EconomyMarketText.priceHistory(view).contains("最后采样价"));
        }
        var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.DAY,END.minusDays(1),END,List.of(row(END.plusMinutes(1),"2.0"),row(END.minusDays(2),"0.5")));
        assertEquals(0,view.count());assertTrue(view.chart().isEmpty());
    }
    @Test void exportsRepresentativeChartUsingDemoSnapshotsOnly() throws Exception {
        List<EconomyMarketQuote> rows=new ArrayList<>();
        var start=END.minusDays(1);
        for(int i=0;i<=144;i++) {
            if(i>=76 && i<=84)continue; // A deliberate gap proves there is no fictitious connecting price path.
            double value=1.08+i*.000024+Math.sin(i*.105)*.0017+Math.sin(i*.61)*.00017;
            rows.add(row(start.plusMinutes(i*10L),String.format(Locale.ROOT,"%.8f",value)));
        }
        var view=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.DAY,start,END,rows);
        byte[] png=new EconomyMarketChartService().render(view);
        var output=Path.of("temp/market-history-candles-preview-demo.png");Files.createDirectories(output.getParent());Files.write(output,png);
        assertTrue(png.length>20000);assertEquals(1,view.gaps());
        var image=ImageIO.read(new ByteArrayInputStream(png));
        // The deliberate missing interval is card background, with no synthetic candle.
        assertEquals(new java.awt.Color(17,28,46).getRGB(),image.getRGB(685,500));
        assertCandleColorsPresent(image);
        // A seven-day preview also demonstrates observed intrabucket highs/lows as real wicks.
        rows.clear();start=END.minusDays(7);
        for(int i=0;i<=1008;i++) {
            double value=1.08+i*.000003+Math.sin(i*.035)*.0015+Math.sin(i*.71)*.00023;
            rows.add(row(start.plusMinutes(i*10L),String.format(Locale.ROOT,"%.8f",value)));
        }
        var weekly=EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,Period.WEEK,start,END,rows);
        assertTrue(weekly.chart().stream().anyMatch(c->c.high().compareTo(c.open().max(c.close()))>0));
        Files.write(Path.of("temp/market-history-candles-week-preview-demo.png"),new EconomyMarketChartService().render(weekly));
    }
    private static void assertCandleColorsPresent(java.awt.image.BufferedImage image) {
        int up=0,down=0;
        for(int x=112;x<=1144;x++)for(int y=284;y<=624;y++) {
            int color=image.getRGB(x,y);
            if(color==new java.awt.Color(143,221,177).getRGB())up++;
            if(color==new java.awt.Color(241,154,168).getRGB())down++;
        }
        assertTrue(up>100,"Rising candle bodies must be green");assertTrue(down>100,"Falling candle bodies must be red");
    }
}
