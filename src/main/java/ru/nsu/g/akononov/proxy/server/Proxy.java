package ru.nsu.g.akononov.proxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.SimpleResolver;
import ru.nsu.g.akononov.proxy.channelAttacments.ChannelAttachment;
import ru.nsu.g.akononov.proxy.channelAttacments.ChannelRole;
import ru.nsu.g.akononov.proxy.channelClient.SocksClient;
import ru.nsu.g.akononov.proxy.dnsResolver.DnsResolver;
import ru.nsu.g.akononov.proxy.messages.connectionMessages.AddressType;
import ru.nsu.g.akononov.proxy.messages.connectionMessages.ConnectionMsg;
import ru.nsu.g.akononov.proxy.messages.connectionMessages.ResponseCode;
import ru.nsu.g.akononov.proxy.channelClient.SocksClientState;
import ru.nsu.g.akononov.proxy.channelReader.ChannelReader;
import ru.nsu.g.akononov.proxy.channelWriter.ChannelWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import static java.nio.channels.SelectionKey.*;

public class Proxy implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(Proxy.class.getSimpleName());

    private static final String GOOGLE_DNS_ADDR = "8.8.8.8";
    private static final int GOOGLE_DNS_PORT = 53;

    private static final int BACKLOG = 10;
    private static final byte SOCKS_VERSION = 0x05;

    private final int proxyPort;
    private DnsResolver dnsResolver;

    private ChannelReader reader;
    private ChannelWriter writer;

    public Proxy(int port) {
        try {
            SimpleResolver simpleResolver = new SimpleResolver(GOOGLE_DNS_ADDR);
            simpleResolver.setPort(GOOGLE_DNS_PORT);

            this.proxyPort = port;
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError();
        }
    }

    @Override
    public void run() {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open();
             Selector selector = Selector.open()) {

            dnsResolver = new DnsResolver(selector, new InetSocketAddress(GOOGLE_DNS_ADDR, GOOGLE_DNS_PORT));

            reader = new ChannelReader(SOCKS_VERSION, proxyPort, dnsResolver);
            writer = new ChannelWriter(dnsResolver);

            logger.info("Waiting incoming connections on port {} ", proxyPort);

            serverSocket.bind(new InetSocketAddress(proxyPort), BACKLOG);
            serverSocket.configureBlocking(false);

            serverSocket.register(selector, OP_ACCEPT);

            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                processSelectedKeys(selectedKeys);
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println(e.getMessage());
        }
    }

    private void processSelectedKeys(Set<SelectionKey> selectedKeys) throws IOException {
        Iterator<SelectionKey> iter = selectedKeys.iterator();
        while (iter.hasNext()) {
            SelectionKey key = iter.next();

            if (key.isValid() && key.isAcceptable()) {
                accept((ServerSocketChannel)key.channel(), key.selector());
            }
            if (key.isValid() && key.isConnectable()) {
                connect(key);
            }
            if (key.isValid() && key.isReadable()) {
                reader.read(key);
            }
            if (key.isValid() && key.isWritable()) {
                writer.write(key);
            }

            iter.remove();
        }
    }

    private void accept(ServerSocketChannel serverSocketChannel, Selector selector) throws IOException {
        SocketChannel clientSocketChannel = serverSocketChannel.accept();

        clientSocketChannel.configureBlocking(false);
        SelectionKey clientSelectionKey = clientSocketChannel.register(selector, OP_READ);

        SocksClient socksClient = new SocksClient(clientSocketChannel, clientSelectionKey);
        clientSelectionKey.attach(new ChannelAttachment(socksClient, ChannelRole.CLIENT));

        logger.info("Incoming connection from {} was ACCEPTED", clientSocketChannel.getRemoteAddress());
    }

    private void connect(SelectionKey selectionKey) throws IOException {
        SocketChannel destSocketChannel = (SocketChannel) selectionKey.channel();
        ChannelAttachment socketChannelRef = (ChannelAttachment) selectionKey.attachment();
        SocksClient socksClient = socketChannelRef.getSocksClient();

        SocketAddress addr = destSocketChannel.getRemoteAddress();

        ConnectionMsg connection = new ConnectionMsg(SOCKS_VERSION,
                AddressType.IPV4_ADDRESS,
                socksClient.getDestAddress().getAddress(),
                socksClient.getDestAddress().getPort());

        try {
            if (!destSocketChannel.finishConnect()) {
                throw new RuntimeException();
            }

            selectionKey.interestOps(0);
            socksClient.getClientSelectionKey().interestOps(OP_WRITE);
            socksClient.setSocksClientState(SocksClientState.SEND_CONN_RESP);
            socksClient.getDestToClientBuffer().put(connection.getResponseBytes(ResponseCode.REQUEST_GRANTED));

            logger.trace("Connected to {}", destSocketChannel.getRemoteAddress());

        } catch (IOException e) {
            socksClient.getDestToClientBuffer().put(connection.getResponseBytes(ResponseCode.HOST_UNREACHABLE));
            socksClient.setCloseUponSending(true);
            socksClient.getClientSelectionKey().interestOps(OP_WRITE);
            socksClient.setSocksClientState(SocksClientState.SEND_CONN_RESP);

            logger.warn("Cannot connect to {}", addr);
        }
    }
}
