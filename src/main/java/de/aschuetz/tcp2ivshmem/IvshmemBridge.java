/*
 * Copyright Alexander Schütz, 2020
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
package de.aschuetz.tcp2ivshmem;

import de.aschuetz.tcp2ivshmem.packets.*;
import de.aschuetz.tcp2ivshmem.sockets.TcpServer;
import de.aschuetz.tcp2ivshmem.sockets.TcpSocket;
import de.aschuetz.tcp2ivshmem.sockets.TcpSocketContainer;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

import static de.aschuetz.tcp2ivshmem.Constants.*;

public abstract class IvshmemBridge {


    private BlockingQueue<AbstractPacket> toIvshmemQueue = new LinkedBlockingQueue<>();

    protected Object mutex = new Object();

    protected OutputStream toIvshmem;

    protected InputStream fromIvshmem;

    protected TcpSocketContainer ownTcpContainer;

    protected TcpSocketContainer otherTcpContainer;

    public IvshmemBridge(int myIndex, int otherIndex) {
        ownTcpContainer = new TcpSocketContainer(this, myIndex, MAX_CONCURRENT_TCP_CONNECTIONS);
        otherTcpContainer = new TcpSocketContainer(this, otherIndex, MAX_CONCURRENT_TCP_CONNECTIONS);
    }

    public void sendUrgentPacket(AbstractPacket packet) throws IOException {
        switch (packet.getPacketEnum()) {
            case CONNECT:
                break;
            case FIN:
                System.out.println("FIN Sending.  Tracking id: " + ((Packet2Fin)packet).getId());
                break;
            case RST:
                new Exception(""+((Packet3Rst)packet).getId()).printStackTrace();
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
            while (toIvshmemQueue.size() > PACKET_QUEUE_SIZE) {
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
        Socket socket = new Socket(packet.getHost(), packet.getPort());
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
        if (packet.getBindAddress() == null) {
            serverSocket = new ServerSocket(packet.getBindPort());
        } else {
            serverSocket = new ServerSocket(packet.getBindPort(), 50, InetAddress.getByName(packet.getBindAddress()));
        }

        System.out.println("New TCP Server on " + serverSocket.getLocalSocketAddress() + ".");
        new TcpServer(this, serverSocket, packet.getDestinationAddress(), packet.getDestinationPort()).start();
    }

    public void addServer(ServerSocket server, String destinationAddress, int desintationPort) {
        System.out.println("New TCP Server on " + server.getLocalSocketAddress() + ".");
        new TcpServer(this, server, destinationAddress, desintationPort).start();
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
            index %= MAX_CONCURRENT_TCP_CONNECTIONS;

            sock = ownTcpContainer.add(ownTcpContainer.getOffset() + current, socket);
        }


        sendUrgentPacket(PacketUtil.connect(sock.getId(), remoteAddress, remotePort));
        sock.start();
        System.out.println("New connection to /" + remoteAddress + ":" + remotePort + " from " + socket.getRemoteSocketAddress() + " using " + socket.getLocalPort() + ". We handle the client side connection. Tracking id: " + (sock.getId()));
    }
}
