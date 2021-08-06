package ru.nsu.g.akononov.proxy;

import ru.nsu.g.akononov.proxy.server.Proxy;

public class Main {

    public static void main(String[] args) {
        if(args.length != 1){
            System.err.println("Bad arguments, expected only proxy port value");
            return;
        }
        int port = Integer.parseInt(args[0]);

        try {
            Proxy proxy = new Proxy(port);
            proxy.run();
        } catch (Exception exception){
            exception.printStackTrace();
        }
    }
}
