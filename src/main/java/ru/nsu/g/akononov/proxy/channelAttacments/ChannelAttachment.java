package ru.nsu.g.akononov.proxy.channelAttacments;

import ru.nsu.g.akononov.proxy.channelClient.SocksClient;

public class ChannelAttachment {
    private final ChannelRole socketChannelRole;
    private final SocksClient socksClient;

    public ChannelAttachment(SocksClient socksClient, ChannelRole socketChannelSide) {
        this.socksClient = socksClient;
        this.socketChannelRole = socketChannelSide;
    }

    public SocksClient getSocksClient() {
        return socksClient;
    }

    public boolean isClient(){
        return socketChannelRole == ChannelRole.CLIENT;
    }

    public boolean isDestination(){
        return socketChannelRole == ChannelRole.DESTINATION;
    }

    public boolean isDNS(){
        return socketChannelRole == ChannelRole.DNS;
    }
}