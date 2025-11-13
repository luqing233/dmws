package fun.luqing.dmws.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DmwConfig {
    private String ws_url;
    private String http_url;
    private int max_messages=40;
    private long bot_id;
    private String bot_name;
    private long[] master;
    private String ai_character;
    private double token_exchange_rate = 0;
    private String default_model;
    private String deepseek_api_key;
    private String deepseek_model;
    private String doubao_api_key;
    private String doubao_model;

    /**
     * 判断指定ID是否为主人
     * @param id 要检查的ID
     * @return 如果是主人返回true，否则返回false
     */
    public boolean isMaster(long id) {
        if (master == null) {
            return false;
        }
        for (long masterId : master) {
            if (masterId == id) {
                return true;
            }
        }
        return false;
    }
}