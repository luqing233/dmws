package fun.luqing.dmws.game.homeaction.impl;

import fun.luqing.dmws.game.MessageContext;
import fun.luqing.dmws.game.baseaction.impl.BaseActionImpl;
import fun.luqing.dmws.game.homeaction.HomeAction;

public class HomeActionImpl extends BaseActionImpl {
    public HomeActionImpl(MessageContext messageContext) {
        super(messageContext);
    }

    @Override
    public void register() {

        System.out.println("register");

    }
}
