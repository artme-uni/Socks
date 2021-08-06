package ru.nsu.g.akononov.proxy.channelWriter;

import ru.nsu.g.akononov.proxy.channelClient.SocksClient;
import ru.nsu.g.akononov.proxy.channelClient.SocksClientState;
import ru.nsu.g.akononov.proxy.channelAttacments.ChannelAttachment;
import ru.nsu.g.akononov.proxy.dnsResolver.DnsResolver;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static java.nio.channels.SelectionKey.OP_READ;
import static java.nio.channels.SelectionKey.OP_WRITE;

public class ChannelWriter {

    private final DnsResolver dnsResolver;

    public ChannelWriter(DnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }


    public void write(SelectionKey selectionKey) throws IOException {

        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        ChannelAttachment channelAttachment = (ChannelAttachment) selectionKey.attachment();
        SocksClient socksClient = channelAttachment.getSocksClient();

        if (channelAttachment.isClient()) {
            writeToClient(socketChannel, socksClient, selectionKey);
        } else if (channelAttachment.isDestination()) {
            writeToDestination(socksClient, socketChannel, selectionKey);
        } else if(channelAttachment.isDNS()){
            dnsResolver.sendDNSRequest(selectionKey);
        }
    }

    private void writeToClient(SocketChannel socketChannel, SocksClient socksClient, SelectionKey selectionKey) throws IOException {
        socksClient.getDestToClientBuffer().flip();
        long bytesCount = socketChannel.write(socksClient.getDestToClientBuffer());

        SocksClientState state = socksClient.getSocksClientState();
        switch (state) {
            case SEND_GREETING_RESP:
            case SEND_CONN_RESP:
                if (socksClient.isCloseUponSending()) {
                    socksClient.closeClientSide();
                    break;
                }
                processClientResponseState(socksClient, state);
                break;
            case ACTIVE:
                processClientActiveState(bytesCount > 0, socksClient, selectionKey);
                break;
            case CLOSED: {
                if (socksClient.getDestToClientBuffer().remaining() == 0) {
                    socksClient.closeClientSide();
                }
                break;
            }
            default:
                throw new IllegalArgumentException();
        }

        socksClient.getDestToClientBuffer().compact();
    }

    private void processClientResponseState(SocksClient socksClient, SocksClientState state) {
        if (socksClient.getDestToClientBuffer().remaining() == 0) {
            if (state == SocksClientState.SEND_CONN_RESP) {
                socksClient.setSocksClientState(SocksClientState.ACTIVE);
                socksClient.getDestSelectionKey().interestOps(OP_READ);
            } else {
                socksClient.setSocksClientState(SocksClientState.RECV_CONN_REQ);
            }
            socksClient.getClientSelectionKey().interestOps(OP_READ);
        }
    }

    private void processClientActiveState(boolean isSmtWrote, SocksClient socksClient, SelectionKey selectionKey) throws IOException {
        if (socksClient.getDestToClientBuffer().remaining() == 0) {
            selectionKey.interestOps(selectionKey.interestOps() & ~OP_WRITE);

            if (socksClient.getSocksClientState() == SocksClientState.CLOSED) {
                socksClient.closeDestSide();
                return;
            }
        }
        if (isSmtWrote) {
            socksClient.getDestSelectionKey().interestOps(
                    socksClient.getDestSelectionKey().interestOps() | OP_READ);
        }
    }

    private void writeToDestination(SocksClient socksClient, SocketChannel socketChannel, SelectionKey selectionKey) throws IOException {
        socksClient.getClientToDestBuffer().flip();
        long bytesCount = socketChannel.write(socksClient.getClientToDestBuffer());

        if (socksClient.getClientToDestBuffer().remaining() == 0) {
            selectionKey.interestOps(selectionKey.interestOps() & ~OP_WRITE);

            if (socksClient.getSocksClientState() == SocksClientState.CLOSED) {
                socksClient.closeDestSide();
                return;
            }
        }
        if (bytesCount > 0) {
            socksClient.getClientSelectionKey().interestOps(
                    socksClient.getClientSelectionKey().interestOps() | OP_READ);
        }
        socksClient.getClientToDestBuffer().compact();
    }
}

