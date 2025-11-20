package fun.luqing.dmws.service.old.ai;

import org.json.JSONArray;

/**
 * 通用 AI 聊天接口，各模型统一实现。
 */
public interface AiService {

    /**
     * 执行 AI 聊天
     * @param messages 历史上下文消息（JSON 数组格式，包含 role, content）
     * @return AiResult 记录类，包含 content 和 totalTokens
     */
    AiResult chat(JSONArray messages);
}