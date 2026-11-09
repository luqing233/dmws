package fun.luqing.dmws.service.dmw.economy;

import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.*;

/** Pure rendering of persisted snapshots, with explicit discontinuities and no generated prices. */
@Service
public class EconomyMarketChartService {
    private static final int WIDTH=1280, HEIGHT=760, LEFT=112, RIGHT=1144, TOP=284, BOTTOM=624;
    private static final Color BG=new Color(11,18,32), CARD=new Color(17,28,46), TEXT=new Color(231,239,249),
            MUTED=new Color(139,159,182), GRID=new Color(41,57,79), CYAN=new Color(69,211,220),
            UP=new Color(143,221,177), DOWN=new Color(241,154,168);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String FONT=selectFont();
    private static final boolean CHINESE=new Font(FONT,Font.PLAIN,14).canDisplayUpTo("模拟行情最近采样缺失区间")<0;

    public byte[] render(History history) {
        BufferedImage image = new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG); g.fillRect(0,0,WIDTH,HEIGHT);
            font(g,14,true); g.setColor(CYAN); g.drawString(label("DMW  /  模拟外汇历史行情","DMW  /  SIMULATED FX"),52,44);
            font(g,38,true); g.setColor(TEXT); g.drawString(history.pair().code(),52,96);
            font(g,15,false); g.setColor(MUTED);
            g.drawString(DATE.format(history.start())+"  -  "+DATE.format(history.end())+label("  /  游戏本地时间","  /  GAME LOCAL TIME"),52,126);
            String period=CHINESE?history.period().label:history.period().days==1?"24 HOURS":history.period().days+" DAYS";
            g.setColor(CARD); g.fillRoundRect(1018,55,208,46,18,18); font(g,15,true); g.setColor(TEXT);
            g.drawString(period+"  /  "+history.period().minutes+label("分钟"," MIN"),1038,85);
            metric(g,52,label("最新采样价","LAST OBSERVED"),price(history.last()),TEXT);
            metric(g,348,label("区间涨跌幅","RANGE CHANGE"),percent(history.change()),history.change()!=null && history.change().signum()<0?DOWN:UP);
            metric(g,644,label("观测最高价","OBSERVED HIGH"),price(history.high()),TEXT);
            metric(g,940,label("观测最低价","OBSERVED LOW"),price(history.low()),TEXT);
            g.setColor(CARD); g.fillRoundRect(52,244,1176,434,22,22);
            if(history.count()==0) {
                font(g,24,true); g.setColor(MUTED); centered(g,label("此区间暂无行情快照","NO SNAPSHOTS IN THIS RANGE"),640,455);
            } else drawPlot(g,history);
            font(g,13,false); g.setColor(MUTED);
            g.drawString(history.count()+label("条原始快照  /  "," raw snapshots  /  ")+history.chart().size()+label("根采样K线  /  "," observed candles  /  ")+history.gaps()+label("处内部缺口"," internal gaps"),56,710);
            String status=history.partial()?label("数据未完整覆盖  /  缺失时段留空，不补造走势","PARTIAL COVERAGE  /  Missing intervals are left blank"):label("已覆盖查询区间","COMPLETE COVERAGE");
            g.setColor(history.partial()?new Color(246,192,113):CYAN); font(g,13,true); g.drawString(status,56,737);
            font(g,12,false); g.setColor(MUTED); right(g,label("服务器模拟行情  /  非真实汇率","SAMPLED GAME PRICES  /  NOT REAL EXCHANGE RATES"),1224,737);
        } finally { g.dispose(); }
        try(var out=new ByteArrayOutputStream()) {
            if(!ImageIO.write(image,"png",out)) throw new IOException("PNG encoder unavailable");
            return out.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("无法生成历史行情图",e); }
    }

    private void drawPlot(Graphics2D g,History h) {
        double low=h.low().doubleValue(),high=h.high().doubleValue(),span=high-low;
        double pad=Math.max(span*.16,Math.max(high*.0002,1e-8)); low-=pad; high+=pad;
        final double min=low,max=high;
        long total=Math.max(1,Duration.between(h.start(),h.end()).toSeconds());
        java.util.function.ToDoubleFunction<Candle> x=p->LEFT+Duration.between(h.start(),p.at()).toSeconds()/(double)total*(RIGHT-LEFT);
        java.util.function.ToDoubleFunction<BigDecimal> y=p->BOTTOM-(p.doubleValue()-min)/(max-min)*(BOTTOM-TOP);
        font(g,13,false);
        for(int i=0;i<=4;i++) {
            int py=TOP+(BOTTOM-TOP)*i/4;
            g.setColor(GRID);g.setStroke(new BasicStroke(1));g.drawLine(LEFT,py,RIGHT,py);
            g.setColor(MUTED);right(g,axisPrice(max-(max-min)*i/4),LEFT-14,py+5);
        }
        for(int i=0;i<=4;i++) {
            int px=LEFT+(RIGHT-LEFT)*i/4;
            g.setColor(GRID);g.drawLine(px,TOP,px,BOTTOM);
            String label=DATE.format(h.start().plusSeconds(total*i/4));
            g.setColor(MUTED);centered(g,label,px,BOTTOM+28);
        }
        double nominalStep=(RIGHT-LEFT)*h.period().minutes*60.0/total;
        // Clip boundary candles instead of shifting their real timestamps. Missing intervals stay empty.
        Shape originalClip=g.getClip();
        g.clipRect(LEFT,TOP,RIGHT-LEFT+1,BOTTOM-TOP+1);
        try {
            g.setStroke(new BasicStroke(1.3f));
            for(int i=0;i<h.chart().size();i++) {
                var candle=h.chart().get(i);
                double px=x.applyAsDouble(candle);
                // Gap-split or partial buckets can be closer than a whole nominal bucket.
                double step=nominalStep;
                if(i>0)step=Math.min(step,px-x.applyAsDouble(h.chart().get(i-1)));
                if(i+1<h.chart().size())step=Math.min(step,x.applyAsDouble(h.chart().get(i+1))-px);
                double width=Math.min(16,step*.6);
                g.setColor(candle.close().compareTo(candle.open())>=0?UP:DOWN);
                g.draw(new Line2D.Double(px,y.applyAsDouble(candle.high()),px,y.applyAsDouble(candle.low())));
                double bodyTop=y.applyAsDouble(candle.open().max(candle.close()));
                double bodyBottom=y.applyAsDouble(candle.open().min(candle.close()));
                g.fill(new Rectangle2D.Double(px-width/2,bodyTop,width,Math.max(2,bodyBottom-bodyTop)));
            }
            double latestY=y.applyAsDouble(h.last());
            g.setColor(new Color(201,216,168));
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,10,new float[]{4,5},0));
            g.draw(new Line2D.Double(LEFT,latestY,RIGHT,latestY));
        } finally { g.setClip(originalClip); }
        font(g,12,true);g.setColor(UP);
        g.drawString(label("采样K线  /  绿涨红跌  /  影线仅含已观测价格","OBSERVED CANDLES  /  GREEN UP, RED DOWN  /  OBSERVED WICKS ONLY"),LEFT,264);
    }
    private void metric(Graphics2D g,int x,String label,String value,Color color) {
        g.setColor(CARD);g.fillRoundRect(x,154,284,72,16,16);font(g,12,true);g.setColor(MUTED);g.drawString(label,x+18,178);
        font(g,25,true);g.setColor(color);g.drawString(value,x+18,208);
    }
    private static String axisPrice(double value) { return String.format(Locale.ROOT,"%.5f",value); }
    private static String price(BigDecimal p) { return p==null?"--":p.setScale(5,RoundingMode.HALF_UP).toPlainString(); }
    private static String percent(BigDecimal p) { return p==null?"--":(p.signum()>=0?"+":"")+p.movePointRight(2).setScale(3,RoundingMode.HALF_UP)+"%"; }
    private static String selectFont() {
        for(String family:List.of("Microsoft YaHei","Noto Sans CJK SC","WenQuanYi Micro Hei",Font.SANS_SERIF))
            if(new Font(family,Font.PLAIN,14).canDisplayUpTo("模拟行情最近采样缺失区间")<0)return family;
        return Font.SANS_SERIF;
    }
    private static String label(String chinese,String english) { return CHINESE?chinese:english; }
    private static void font(Graphics2D g,int size,boolean bold) { g.setFont(new Font(FONT,bold?Font.BOLD:Font.PLAIN,size)); }
    private static void right(Graphics2D g,String text,int x,int y) { g.drawString(text,x-g.getFontMetrics().stringWidth(text),y); }
    private static void centered(Graphics2D g,String text,int x,int y) { g.drawString(text,x-g.getFontMetrics().stringWidth(text)/2,y); }
}
