package fun.luqing.dmws.game;


import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;

/*public record MessageContext(Bot bot, GroupMessageEvent event) {

*//*    public Long userId() {
        return event.getUserId();
    }

    public Long groupId() {
        return event.getGroupId();
    }*//*

}*/
public record MessageContext(int bot, int event) {

/*    public Long userId() {
        return event.getUserId();
    }

    public Long groupId() {
        return event.getGroupId();
    }*/

}