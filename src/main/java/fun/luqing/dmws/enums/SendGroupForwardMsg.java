package fun.luqing.dmws.enums;

import com.mikuac.shiro.enums.ActionPath;
import lombok.Getter;

@Getter
public enum SendGroupForwardMsg implements ActionPath {
    send_group_forward_msg("send_group_forward_msg");

    private final String path;

    SendGroupForwardMsg(String path) {
        this.path = path;
    }

    @Override
    public String getPath() {
        return this.path;
    }
}
