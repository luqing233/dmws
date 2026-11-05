package fun.luqing.dmws.common.economy;

public class DuplicateEconomyOperationException extends EconomyException {

    public DuplicateEconomyOperationException(String operationId) {
        super("DUPLICATE_OPERATION", "这条消息的操作已处理，本次未重复扣款或消耗");
    }
}
