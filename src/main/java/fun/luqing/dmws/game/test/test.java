package fun.luqing.dmws.game.test;

import fun.luqing.dmws.game.MessageContext;
import fun.luqing.dmws.game.homeaction.impl.HomeActionImpl;

public class test {

    public static void main(String[] args) {
        test t=new test();
        t.t1();
    }

    public void t1(){
        HomeActionImpl homeAction=new HomeActionImpl(new MessageContext(1,1));
        homeAction.register();

    }
}
