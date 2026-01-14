package fun.luqing.dmws.game.baseaction.impl;

import fun.luqing.dmws.game.MessageContext;
import fun.luqing.dmws.game.baseaction.BaseAction;

public class BaseActionImpl implements BaseAction {

    private MessageContext messageContext;


    public BaseActionImpl(MessageContext messageContext) {
        this.messageContext = messageContext;
    }


    @Override
    public void register() {

    }
}
