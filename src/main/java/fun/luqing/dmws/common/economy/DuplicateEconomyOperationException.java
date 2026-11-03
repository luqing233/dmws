package fun.luqing.dmws.common.economy;

public class DuplicateEconomyOperationException extends EconomyException {

    public DuplicateEconomyOperationException(String operationId) {
        super("DUPLICATE_OPERATION", "该操作已经处理: " + operationId);
    }
}
