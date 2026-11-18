package fun.luqing.dmws.economy;

import fun.luqing.dmws.service.dmw.economy.EconomyFishingService;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingService.FishingResult;

final class FishingTestActions {
    private FishingTestActions() { }

    static FishingResult fish(EconomyFishingService service, long userId, String nickname, String operationId) {
        return fish(service, userId, nickname, operationId, null, null, null);
    }

    static FishingResult fish(EconomyFishingService service, long userId, String nickname, String operationId,
                              Long groupId, String groupName) {
        return fish(service, userId, nickname, operationId, groupId, groupName, null);
    }

    static FishingResult fish(EconomyFishingService service, long userId, String nickname, String operationId,
                              Long groupId, String groupName, String bait) {
        var ticket = service.cast(userId, nickname, operationId, 0, groupId, groupName, null, bait);
        return service.reel(userId, operationId, ticket.dueAt());
    }
}
