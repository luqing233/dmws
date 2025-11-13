package fun.luqing.dmws.utils;

import fun.luqing.dmws.enums.AiStatus;

public record AiResponse(AiStatus status, String response, String errorMessage, Long total_tokens) {
    // 便捷方法，创建成功的响应
    public static AiResponse success(String response, long total_tokens) {
        return new AiResponse(AiStatus.SUCCESS, response, null,total_tokens);
    }

    // 便捷方法，创建用户忙碌的响应
    public static AiResponse busy() {
        return new AiResponse(AiStatus.BUSY, null, "正在处理你的上一条消息，请稍后再试！",null);
    }

    // 便捷方法，创建错误的响应
    public static AiResponse error(String errorMessage) {
        return new AiResponse(AiStatus.ERROR, null, errorMessage,null);
    }
}

