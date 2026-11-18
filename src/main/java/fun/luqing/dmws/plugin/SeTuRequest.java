package fun.luqing.dmws.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record SeTuRequest(List<String> tags, int num, int r18, List<String> sizes, String proxy, boolean excludeAI) {
    static SeTuRequest parse(String text) {
        String content = text.replaceFirst("^涩图\\s*", "").trim();
        content = content.replaceAll("[,，](?=[a-zA-Z][a-zA-Z0-9]*=)", " ");
        if (content.isEmpty()) throw new IllegalArgumentException("用法：涩图 tag=标签1 标签2 [num=1-5]\n例如：涩图 tag=风景 num=2\n默认加载常规尺寸，可用 size=original 请求原图。");
        List<String> tags = new ArrayList<>();
        int num = 1;
        int r18 = 0;
        List<String> sizes = List.of("regular");
        String proxy = null;
        boolean excludeAI = false;
        for (String token : content.split("\\s+")) {
            if (!token.contains("=")) {
                tags.add(token);
                continue;
            }
            String[] pair = token.split("=", 2);
            String key = pair[0].toLowerCase(Locale.ROOT);
            String value = pair[1];
            if (value.isBlank()) throw new IllegalArgumentException(key + " 参数不能为空。");
            switch (key) {
                case "tag" -> tags.add(value);
                case "num" -> num = number(value, 1, 5, "num");
                case "r18" -> r18 = number(value, 0, 2, "r18");
                case "size" -> {
                    sizes = List.of(value.split("[,，]", -1));
                    if (!Set.of("original", "regular", "small", "thumb", "mini").containsAll(sizes)) {
                        throw new IllegalArgumentException("size 支持 original、regular、small、thumb、mini，可用逗号分隔。");
                    }
                }
                case "proxy" -> {
                    if (!value.matches("[a-zA-Z0-9.-]+(?::[0-9]{1,5})?")) {
                        throw new IllegalArgumentException("proxy 请填写图片代理域名，例如 i.pixiv.re。");
                    }
                    proxy = value;
                }
                case "excludeai" -> {
                    if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("excludeai 只能填写 true 或 false。");
                    }
                    excludeAI = Boolean.parseBoolean(value);
                }
                default -> throw new IllegalArgumentException("不支持的参数：" + key);
            }
        }
        if (tags.isEmpty()) throw new IllegalArgumentException("请提供标签，例如：涩图 tag=风景 num=2");
        if (tags.size() > 3) throw new IllegalArgumentException("最多填写 3 组标签，组内可用 | 表示任选其一。");
        return new SeTuRequest(List.copyOf(tags), num, r18, sizes, proxy, excludeAI);
    }

    private static int number(String value, int min, int max, String key) {
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(key + " 必须是 " + min + "–" + max + " 之间的整数。");
    }
}
