package com.marinevhf.app;

import com.marinevhf.core.RadioEngine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Вывод NMEA (!AIVDM) на локальный порт: TCP-сервер (клиенты подключаются сами) и UDP на указанный адрес.
 * Без зависимостей от Android — проверяется на JVM.
 */
public final class NmeaServer implements RadioEngine.NmeaSink {
    private final int tcpPort, udpPort;
    private final String udpHost;
    private ServerSocket server;
    private DatagramSocket udp;
    private volatile InetAddress udpAddr;
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nmea-send");
        t.setDaemon(true);
        return t;
    });
    private final List<Socket> clients = new ArrayList<>();
    private volatile boolean run;

    public NmeaServer(int tcpPort, String udpHost, int udpPort) {
        this.tcpPort = tcpPort;
        this.udpHost = udpHost;
        this.udpPort = udpPort;
    }

    public void start() throws IOException {
        run = true;
        udp = new DatagramSocket();
        server = new ServerSocket(tcpPort, 8, InetAddress.getByName("0.0.0.0"));
        Thread t = new Thread(() -> {
            try { udpAddr = InetAddress.getByName(udpHost); } catch (IOException ignored) { }   // DNS — не в главном потоке
            while (run) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    synchronized (clients) { clients.add(s); }
                } catch (IOException e) {
                    return;
                }
            }
        }, "nmea-accept");
        t.setDaemon(true);
        t.start();
    }

    public int clientCount() {
        synchronized (clients) { return clients.size(); }
    }

    /** Не блокирует приёмник: отправка идёт в отдельном потоке (медленный TCP-клиент не тормозит приём). */
    @Override
    public void send(String sentence) {
        byte[] b = (sentence + "\r\n").getBytes(StandardCharsets.US_ASCII);
        try {
            out.execute(() -> deliver(b));
        } catch (RejectedExecutionException ignored) { }
    }

    private void deliver(byte[] b) {
        InetAddress a = udpAddr;
        if (a != null) try {
            udp.send(new DatagramPacket(b, b.length, a, udpPort));
        } catch (IOException ignored) { }
        synchronized (clients) {
            for (Iterator<Socket> it = clients.iterator(); it.hasNext(); ) {
                Socket s = it.next();
                try {
                    OutputStream o = s.getOutputStream();
                    o.write(b);
                    o.flush();
                } catch (IOException e) {
                    try { s.close(); } catch (IOException ignored) { }
                    it.remove();
                }
            }
        }
    }

    public void stop() {
        run = false;
        out.shutdown();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (udp != null) udp.close();
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (IOException ignored) { }
            clients.clear();
        }
    }
}
