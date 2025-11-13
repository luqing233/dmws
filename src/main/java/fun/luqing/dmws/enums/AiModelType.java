package fun.luqing.dmws.enums;

import lombok.Getter;

/**
 * AI 模型类型枚举，用于动态选择 AI 服务 Bean。
 */
@Getter
public enum AiModelType {
    DEEPSEEK("deepseek", "deepSeekAiService"),
    DOUBAO("doubao", "douBaoAiService"); // 预留未来扩展

    private final String value;
    private final String serviceBeanName;

    AiModelType(String value, String serviceBeanName) {
        this.value = value;
        this.serviceBeanName = serviceBeanName;
    }

    public static AiModelType fromString(String value) {
        for (AiModelType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的AI模型: " + value);
    }
}
