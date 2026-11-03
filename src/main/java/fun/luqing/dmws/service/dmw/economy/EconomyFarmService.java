package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyFarmCrop;
import fun.luqing.dmws.entity.dmw.economy.EconomyFarmPlot;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyFarmCropRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFarmPlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EconomyFarmService {

    private static final int DEFAULT_PLOTS = 4;

    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyFarmCropRepository cropRepository;
    private final EconomyFarmPlotRepository plotRepository;

    @Transactional(transactionManager = "dmwTransactionManager")
    public List<PlotView> overview(long userId, String nickname) {
        profileService.getOrCreateForUpdate(userId, nickname);
        ensurePlots(userId);
        return plotRepository.findByUserIdOrderByPlotIndexAsc(userId).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyFarmCrop> crops() {
        return cropRepository.findAllByEnabledTrueOrderBySeedPriceAsc();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public PlotView plant(
            long userId,
            String nickname,
            String cropToken,
            int plotIndex,
            String operationId
    ) {
        validatePlotIndex(plotIndex);
        profileService.getOrCreateForUpdate(userId, nickname);
        ensurePlots(userId);
        EconomyFarmPlot plot = lockPlot(userId, plotIndex);
        if (plot.getCropCode() != null) {
            throw new EconomyException("FARM_PLOT_OCCUPIED", "该地块已经有作物了");
        }

        EconomyFarmCrop crop = resolveCrop(cropToken);
        operationService.start(operationId, "FARM_PLANT", userId, "种植 " + crop.getCode());
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
                EconomyMoney.positive(crop.getSeedPrice()),
                operationId,
                EconomyLedgerReason.FARM_SEED,
                userId,
                "购买种子 " + crop.getName()
        );
        LocalDateTime now = LocalDateTime.now();
        plot.setCropCode(crop.getCode());
        plot.setPlantedAt(now);
        plot.setReadyAt(now.plusSeconds(crop.getGrowthSeconds()));
        plot.setWatered(false);
        plot.setUpdatedAt(now);
        plotRepository.save(plot);
        return view(plot);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public PlotView water(long userId, String nickname, int plotIndex) {
        validatePlotIndex(plotIndex);
        profileService.getOrCreateForUpdate(userId, nickname);
        ensurePlots(userId);
        EconomyFarmPlot plot = lockPlot(userId, plotIndex);
        if (plot.getCropCode() == null) {
            throw new EconomyException("FARM_PLOT_EMPTY", "该地块没有作物");
        }
        if (Boolean.TRUE.equals(plot.getWatered())) {
            throw new EconomyException("FARM_ALREADY_WATERED", "该地块已经浇过水了");
        }
        LocalDateTime now = LocalDateTime.now();
        if (plot.getReadyAt() != null && plot.getReadyAt().isAfter(now)) {
            long remaining = Math.max(1, Duration.between(now, plot.getReadyAt()).toSeconds());
            plot.setReadyAt(now.plusSeconds(Math.max(1, remaining * 4 / 5)));
        }
        plot.setWatered(true);
        plot.setUpdatedAt(now);
        plotRepository.save(plot);
        return view(plot);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public HarvestView harvest(long userId, String nickname, int plotIndex, String operationId) {
        validatePlotIndex(plotIndex);
        profileService.getOrCreateForUpdate(userId, nickname);
        ensurePlots(userId);
        EconomyFarmPlot plot = lockPlot(userId, plotIndex);
        if (plot.getCropCode() == null || plot.getReadyAt() == null) {
            throw new EconomyException("FARM_PLOT_EMPTY", "该地块没有可收获的作物");
        }
        LocalDateTime now = LocalDateTime.now();
        if (plot.getReadyAt().isAfter(now)) {
            long remaining = Duration.between(now, plot.getReadyAt()).toSeconds();
            throw new EconomyException("FARM_NOT_READY", "作物尚未成熟，还需 " + remaining + " 秒");
        }

        EconomyFarmCrop crop = cropRepository.findById(plot.getCropCode())
                .orElseThrow(() -> new EconomyException("CROP_NOT_FOUND", "作物配置不存在"));
        int yield = ThreadLocalRandom.current().nextInt(
                Math.max(1, crop.getYieldMin()),
                Math.max(crop.getYieldMin(), crop.getYieldMax()) + 1
        );
        BigDecimal reward = EconomyMoney.positive(
                crop.getSellPrice().multiply(BigDecimal.valueOf(yield))
                        .setScale(2, RoundingMode.HALF_UP)
        );
        operationService.start(operationId, "FARM_HARVEST", userId, "收获 " + crop.getCode());
        ledgerService.creditUser(
                userId,
                EconomyAccountScope.WALLET,
                reward,
                operationId,
                EconomyLedgerReason.FARM_HARVEST,
                userId,
                "收获 " + crop.getName() + " x" + yield
        );
        plot.setCropCode(null);
        plot.setPlantedAt(null);
        plot.setReadyAt(null);
        plot.setWatered(false);
        plot.setUpdatedAt(now);
        plotRepository.save(plot);
        return new HarvestView(crop.getName(), yield, reward, view(plot));
    }

    private void ensurePlots(long userId) {
        List<EconomyFarmPlot> existing = plotRepository.findByUserIdOrderByPlotIndexAsc(userId);
        if (existing.size() >= DEFAULT_PLOTS) {
            return;
        }
        for (int index = 1; index <= DEFAULT_PLOTS; index++) {
            int plotIndex = index;
            if (existing.stream().noneMatch(plot -> plot.getPlotIndex() == plotIndex)) {
                EconomyFarmPlot plot = new EconomyFarmPlot();
                plot.setUserId(userId);
                plot.setPlotIndex(plotIndex);
                plot.setWatered(false);
                plotRepository.save(plot);
            }
        }
        plotRepository.flush();
    }

    private EconomyFarmPlot lockPlot(long userId, int plotIndex) {
        return plotRepository.findForUpdate(userId, plotIndex)
                .orElseThrow(() -> new EconomyException("FARM_PLOT_MISSING", "地块不存在"));
    }

    private EconomyFarmCrop resolveCrop(String token) {
        return cropRepository.findById(token)
                .or(() -> cropRepository.findFirstByEnabledTrueAndName(token))
                .filter(EconomyFarmCrop::getEnabled)
                .orElseThrow(() -> new EconomyException("CROP_NOT_FOUND", "作物不存在: " + token));
    }

    private PlotView view(EconomyFarmPlot plot) {
        if (plot.getCropCode() == null || plot.getReadyAt() == null) {
            return new PlotView(plot.getPlotIndex(), "空地", false, null, 0);
        }
        long remaining = Math.max(0, Duration.between(LocalDateTime.now(), plot.getReadyAt()).toSeconds());
        String cropName = cropRepository.findById(plot.getCropCode())
                .map(EconomyFarmCrop::getName)
                .orElse(plot.getCropCode());
        return new PlotView(
                plot.getPlotIndex(),
                cropName,
                Boolean.TRUE.equals(plot.getWatered()),
                plot.getReadyAt(),
                remaining
        );
    }

    private void validatePlotIndex(int plotIndex) {
        if (plotIndex < 1 || plotIndex > DEFAULT_PLOTS) {
            throw new EconomyException("INVALID_PLOT", "地块编号必须在 1 到 " + DEFAULT_PLOTS + " 之间");
        }
    }

    public record PlotView(
            int plotIndex,
            String cropName,
            boolean watered,
            LocalDateTime readyAt,
            long remainingSeconds
    ) {
    }

    public record HarvestView(
            String cropName,
            int yield,
            BigDecimal reward,
            PlotView plot
    ) {
    }
}
