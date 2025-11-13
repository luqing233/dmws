package fun.luqing.dmws.enums;

import com.mikuac.shiro.enums.ActionPath;
import lombok.Getter;

@Getter
public enum GetForwardMsg implements ActionPath{
    get_forward_msg("get_forward_msg");

    private final String path;
    GetForwardMsg(String path) {
        this.path = path;
    }

    @Override
    public String getPath() {
        return this.path;
    }
}