/*
 * Copyright Alexander Schütz, 2020-2022
 *
 * This file is part of tcp2ivshmem.
 *
 * tcp2ivshmem is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * tcp2ivshmem is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * A copy of the GNU General Public License should be provided
 * in the COPYING file in top level directory of tcp2ivshmem.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.alexanderschuetz97.tcp2ivshmem.ivshmem;

import io.github.alexanderschuetz97.tcp2ivshmem.Main;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.AbstractPacket;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet1Connect;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet3Rst;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet4Data;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet6OpenServerResult;
import io.github.alexanderschuetz97.tcp2ivshmem.servers.Socks5Server;
import io.github.alexanderschuetz97.tcp2ivshmem.servers.TcpServer;
import io.github.alexanderschuetz97.tcp2ivshmem.sockets.TcpSocket;
import io.github.alexanderschuetz97.tcp2ivshmem.sockets.TcpSocketContainer;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet2Fin;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.Packet5OpenServer;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.PacketUtil;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public abstract class IvshmemBridge {


    private BlockingQueue<AbstractPacket> toIvshmemQueue = new LinkedBlockingQueue<>();

    protected Object mutex = new Object();

    protected OutputStream toIvshmem;

    protected InputStream fromIvshmem;

    protected TcpSocketContainer ownTcpContainer;

    protected TcpSocketContainer otherTcpContainer;

    protected void init(int myIndex, int otherIndex) {
        ownTcpContainer = new TcpSocketContainer(this, myIndex, Main.maxConcurrentTcpConnections);
        otherTcpContainer = new TcpSocketContainer(this, otherIndex, Main.maxConcurrentTcpConnections);
    }

    public void sendUrgentPacket(AbstractPacket packet) throws IOException {
        switch (packet.getPacketEnum()) {
            case CONNECT:
                break;
            case FIN:
                System.out.println("FIN Sending.  Tracking id: " + ((Packet2Fin)packet).getId());
                break;
            case RST:
                System.out.println("RST Sending.  Tracking id: " +  ((Packet3Rst)packet).getId());
                break;
            case DATA:
                break;
            case SERVER:
                break;
        }
        try {
            toIvshmemQueue.put(packet);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public void sendPacket(AbstractPacket packet) throws IOException {
        synchronized (mutex) {
            while (toIvshmemQueue.size() > Constants.PACKET_QUEUE_SIZE) {
                    try {
                        mutex.wait();
                    } catch (InterruptedException e) {
                        //DC.
                    }
            }
        }

        try {
            toIvshmemQueue.put(packet);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    public void start() {
        try {
            connectToIvshmem();
        } catch (Exception exc) {
            System.out.println("Error connecting to endpoint via shared memory.");
            exc.printStackTrace();
            System.exit(-1);
            return;
        }

        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    write();
                } catch (Exception exc) {
                    System.out.println("Error writing to shared memory.");
                    exc.printStackTrace();
                    System.exit(-1);
                    return;
                }
                System.exit(0);
            }
        });

        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    read();
                } catch (Exception e) {
                    System.out.println("Error reading from shared memory.");
                    e.printStackTrace();
                    System.exit(-1);
                    return;
                }
                System.exit(0);
            }
        });
    }

    protected abstract void connectToIvshmem() throws Exception;

    protected void handleConnect(Packet1Connect packet) throws IOException {

        Socket socket;
        try {
            socket = new Socket(packet.getHost(), packet.getPort());
        } catch (IOException exc) {
            System.out.println("New connection to /" + packet.getHost() + ":" + packet.getPort() + " failed " + exc.getMessage());
            sendUrgentPacket(PacketUtil.rst(packet.getId()));
            otherTcpContainer.rst(packet.getId());
            return;
        }
        System.out.println("New connection to /" + packet.getHost() + ":" + packet.getPort() + " from " + socket.getLocalPort() + ". We handle the server side connection. Tracking id: " + packet.getId());
        otherTcpContainer.add(packet.getId(), socket).start();
    }

    protected void handleFin(Packet2Fin packet) throws IOException {
        int id = packet.getId();
        System.out.println("FIN Received. Tracking id: " + id);
        if (otherTcpContainer.isID(id)) {
            otherTcpContainer.fin(id);
        } else {
            ownTcpContainer.fin(id);
        }

    }

    protected void handleRst(Packet3Rst packet) throws IOException {
        int id = packet.getId();
        System.out.println("RST Received. Tracking id: " + id);

        if (otherTcpContainer.isID(id)) {
            otherTcpContainer.rst(id);
        } else {
            ownTcpContainer.rst(id);
        }
    }

    protected void handleData(Packet4Data packet) throws IOException {
        int id = packet.getId();
        if (otherTcpContainer.isID(id)) {
            otherTcpContainer.data(id, packet.getData());
        } else {
            ownTcpContainer.data(id, packet.getData());
        }
    }

    protected void handleServer(Packet5OpenServer packet) throws IOException {

        ServerSocket serverSocket;
        try {
            if (packet.getBindAddress() == null) {
                serverSocket = new ServerSocket(packet.getBindPort());
            } else {

                serverSocket = new ServerSocket(packet.getBindPort(), 50, InetAddress.getByName(packet.getBindAddress()));
            }
        } catch (Exception exc) {
            System.out.println("New TCP Server on addr " + packet.getBindAddress() + " port " + packet.getBindPort() + " failed " + exc.getMessage());
            sendUrgentPacket(PacketUtil.serverResult(packet.getId(), false));
            return;
        }
        sendUrgentPacket(PacketUtil.serverResult(packet.getId(), true));

        System.out.println("New TCP Server on " + serverSocket.getLocalSocketAddress() + ".");
        new TcpServer(this, serverSocket, packet.getDestinationAddress(), packet.getDestinationPort()).start();
    }

    protected final OpenServerTransferObject[] openServerTransferObjects = new OpenServerTransferObject[16];

    protected void handleServerResult(Packet6OpenServerResult packet) {
        OpenServerTransferObject obj = openServerTransferObjects[packet.getId() % openServerTransferObjects.length];
        if (obj == null) {
            System.out.println("Received unexpected open server result");
            return;
        }
        synchronized (obj) {
            if (obj.id != packet.getId()) {
                System.out.println("Received unexpected open server result");
                return;
            }

            obj.result = packet;
            obj.notifyAll();
        }
    }


    public void addServer(ServerSocket server, String destinationAddress, int destinationPort) {
        System.out.println("New TCP Server on " + server.getLocalSocketAddress() + ".");
        new TcpServer(this, server, destinationAddress, destinationPort).start();
    }

    public void addSocks5Proxy(ServerSocket server) {
        System.out.println("New Socks5 Server on " + server.getLocalSocketAddress() + ".");
        new Socks5Server(this, server).start();
    }



    protected void write() throws Exception {
        Thread.currentThread().setName("Ivshmem writer Thread");
        DataOutputStream dout = new DataOutputStream(toIvshmem);
        while(true) {
            AbstractPacket tempPacket = toIvshmemQueue.take();


            PacketUtil.writePacket(tempPacket, dout);

            synchronized (mutex) {
                mutex.notifyAll();
            }
        }
    }


    protected void read() throws Exception {
        Thread.currentThread().setName("Ivshmem reader Thread");

        DataInputStream din = new DataInputStream(fromIvshmem);
        while(true) {
            AbstractPacket packet = PacketUtil.readPacket(din);

            switch (packet.getPacketEnum()) {
                case CONNECT:
                    handleConnect((Packet1Connect) packet);
                    break;
                case FIN:
                    handleFin((Packet2Fin) packet);
                    break;
                case RST:
                    handleRst((Packet3Rst) packet);
                    break;
                case DATA:
                    handleData((Packet4Data) packet);
                    break;
                case SERVER:
                    handleServer((Packet5OpenServer) packet);
                    break;
                case SERVER_RESULT:
                    handleServerResult((Packet6OpenServerResult) packet);
                    break;
                default:
                    System.out.println("Received invalid packet " + packet);
                    System.exit(-1);
                    return;
            }
        }
    }

    private Object addSocketMutex = new Object();
    private int index = 0;

    public void addTcpSocket(Socket socket, String remoteAddress, int remotePort) throws IOException {

        TcpSocket sock;
        synchronized (addSocketMutex) {
            int current = index++;
            index %= Main.maxConcurrentTcpConnections;

            sock = ownTcpContainer.add(ownTcpContainer.getOffset() + current, socket);
        }


        sendUrgentPacket(PacketUtil.connect(sock.getId(), remoteAddress, remotePort));
        sock.start();
        System.out.println("New connection to /" + remoteAddress + ":" + remotePort + " from " + socket.getRemoteSocketAddress() + " using " + socket.getLocalPort() + ". We handle the client side connection. Tracking id: " + (sock.getId()));
    }

    public boolean openServerOnRemote(String bindAddr, int port, String destinationAddress, int destinationPort) throws IOException {
        Packet5OpenServer srv = PacketUtil.server(bindAddr, port, destinationAddress, destinationPort);
        OpenServerTransferObject transferObject = new OpenServerTransferObject();
        transferObject.id = srv.getId();
        synchronized (openServerTransferObjects) {
            while (openServerTransferObjects[srv.getId() % openServerTransferObjects.length] != null) {
                try {
                    openServerTransferObjects.wait();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }

            openServerTransferObjects[srv.getId() % openServerTransferObjects.length] = transferObject;
        }

        boolean success = false;

        sendUrgentPacket(srv);
        synchronized (transferObject) {
            if (transferObject.result == null) {
                try {
                    transferObject.wait(15000);
                } catch (InterruptedException e) {
                    throw new IOException();
                }
            }

            if (transferObject.result == null) {
                //TODO better handling.
                System.out.println("Timeout while waiting for remote to open a server!");
                System.exit(-1);
                return false;
            }

            success = transferObject.result.isSuccess();
        }

        synchronized (openServerTransferObjects) {
            openServerTransferObjects[srv.getId() % openServerTransferObjects.length] = null;
            openServerTransferObjects.notifyAll();
        }


        if (bindAddr == null) {
            bindAddr = "*";
        }

        if (success) {
            System.out.println("Opening a remote TCP server succeeded bind address " + bindAddr + " bind port " + port + " destination " + destinationAddress + " destination port " + destinationPort);
        } else {
            System.out.println("Opening a remote TCP server failed bind address " + bindAddr + " bind port " + port + " destination " + destinationAddress + " destination port " + destinationPort);
        }
        return success;
    }

    //Transfer object for inter thread communication. CompletableFuture is in JDK 8 sadly and i dont want to include GUAVA for SettableFuture...
    class OpenServerTransferObject {
        int id;
        volatile Packet6OpenServerResult result;
    }


}
