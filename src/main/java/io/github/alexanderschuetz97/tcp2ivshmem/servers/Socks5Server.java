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
package io.github.alexanderschuetz97.tcp2ivshmem.servers;

import io.github.alexanderschuetz97.tcp2ivshmem.Main;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.IvshmemBridge;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

public class Socks5Server {

    private static final AtomicInteger ID = new AtomicInteger(1);

    private final ServerSocket server;

    private final IvshmemBridge ivshmemBridge;

    public Socks5Server(IvshmemBridge ivshmemBridge, ServerSocket tpcSocket) {
        this.server = tpcSocket;
        this.ivshmemBridge = ivshmemBridge;
    }

    public void start() {
        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        });
    }

    private void accept() {
        while(!server.isClosed()) {
            Socket socket = null;
            int id = -1;
            try {
                socket = server.accept();
                id = ID.getAndIncrement();
                System.out.println("Socks id " + id +" new connection from " + socket.getRemoteSocketAddress() + " to " + socket.getLocalSocketAddress());
                handleSocks(id, socket);
            } catch (IOException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                if (id != -1) {
                    System.out.println("Socks5 id " + id + " disconnected. Error " + e.getMessage());
                }
                e.printStackTrace();
            }
        }
    }

    //https://tools.ietf.org/html/rfc1928
    private void handleSocks(int id, Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        DataInputStream din = new DataInputStream(socket.getInputStream());
        DataOutputStream dout = new DataOutputStream(socket.getOutputStream());

        readMethod(id,socket,din);

        int nmethods = din.readUnsignedByte();
        if (nmethods < 1) {
            System.out.println("Socks5 id " + id + " send that is supports no supported methods of connecting. It is probably not speaking the socks protocol. Disconnecting!");
        }

        boolean tempSupportsNoAuth = false;
        byte[] tempBaos = new byte[nmethods];
        din.readFully(tempBaos);
        for (int i = 0; i < nmethods; i++) {
            if (tempBaos[i] == 0) {
                tempSupportsNoAuth = true;
                break;
            }
        }

        if (!tempSupportsNoAuth) {
            System.out.println("Socks5 id " + id + " send that it requires authentication to connect. This is not supported. Disconnecting.");
            dout.write(5);
            dout.write(0xff);
            dout.flush();
            socket.close();
            return;
        }

        dout.write(5);
        dout.write(0);
        dout.flush();

        readMethod(id,socket,din);
        int command = din.readUnsignedByte();
        if (command > 1) {
            if (command == 3) {
                System.out.println("Socks5 id " + id + " requested udp connection this is unsupported. Disconnecting.");
            } else {
                System.out.println("Socks5 id " + id + " send unsupported command " + command + ". Disconnecting.");
            }
            dout.write(5);
            dout.write(7);
            dout.flush();
            socket.close();
        }

        //reserved...
        din.readUnsignedByte();
        int atyp = din.readUnsignedByte();
        String address;
        switch (atyp) {
            case(1):
                byte[] ipv4 = new byte[4];
                din.readFully(ipv4);
                ;
                address =  String.valueOf(ipv4[0] & 0xff) + "." + String.valueOf(ipv4[1] & 0xff) + "." + String.valueOf(ipv4[2] & 0xff) + "." + String.valueOf(ipv4[3] & 0xff);
                break;
            case(3):
                int len = din.readUnsignedByte();
                if (len == 0) {
                    System.out.println("Socks5 id " + id + " send domain length with 0 characters. Disconnecting.");
                    dout.write(5);
                    dout.write(1);
                    dout.flush();
                    socket.close();
                    return;
                }
                byte[] domain = new byte[len];
                din.readFully(domain);
                address = new String(domain, Charset.forName("UTF-8"));
                break;
            case(4):
                byte[] ipv6 = new byte[16];
                din.readFully(ipv6);
                address = parseIv6(ipv6);
                break;
            default:
                System.out.println("Socks5 id " + id + " send unsupported atyp " + atyp + ". Disconnecting.");
                dout.write(5);
                dout.write(8);
                dout.flush();
                socket.close();
                return;
        }

        int port = din.readUnsignedShort();
        if (port == 0) {
            System.out.println("Socks5 id " + id + " send port number zero. Disconnecting.");
            dout.write(5);
            dout.write(1);
            dout.flush();
            socket.close();
            return;
        }

        //Normally we would send this after we connected succesfully but we cant do that yet... TODO
        dout.write(5);
        dout.write(0);
        dout.write(0);
        //fake ipv4 we have no way to know the real ip endpoint... yet... TODO
        dout.write(1);
        dout.write(0);
        dout.write(0);
        dout.write(0);
        dout.write(0);
        //fake port we have no way to know the real endpoint yet... TODO
        dout.writeShort(8080);
        System.out.println("Socks id " + id + " negotiation completed. Address " + address + " Port " + port);
        ivshmemBridge.addTcpSocket(socket, address, port);
    }


    private static String parseIv6(byte[] ipv6) {
        String[] sections = new String[8];
        for (int i = 0; i < sections.length; i++) {
            int first = ipv6[2*i] & 0xff;
            int second = ipv6[(2*i)+1] & 0xff;
            boolean firstZero = first == 0;
            boolean secondZero = second == 0;
            if (firstZero && secondZero) {
                continue;
            }

            if (firstZero) {
                sections[i] = Integer.toHexString(second);
            } else {
                if (secondZero) {
                    sections[i] = Integer.toHexString(first)+ "00";
                } else {
                    String secondStr = Integer.toHexString(second);
                    if (secondStr.length() == 1) {
                        sections[i] = Integer.toHexString(first)+ "0" + secondStr;
                    } else {
                        sections[i] = Integer.toHexString(first)+ secondStr;
                    }
                }
            }
        }

        boolean trunc = false;
        StringBuilder tempSB = new StringBuilder();
        for (int i = 0; i < sections.length;i++) {
            String cur = sections[i];

            if (cur == null) {
                if (trunc) {
                    continue;
                }
                String next = i+1 == sections.length ? "" : sections[i+1];
                if (next == null) {
                    trunc = true;
                    tempSB.append("::");
                    continue;
                }

                cur = "0";
            }

            trunc = false;
            if (i != 0) {
                tempSB.append(":");
                tempSB.append(cur);
            }
        }



        String tempStr = tempSB.toString();
        if (tempStr.equals("::")) {
            return "::1";
        }

        return tempStr;
    }

    private void readMethod(int id, Socket socket, DataInputStream din) throws IOException {
        int method = din.readUnsignedByte();
        if (method != 5) {
            System.out.println("Socks5 id " + id + " sent either unsupported socks protocol version or is not speaking the socks protocol. Disconnecting!");
            socket.close();
            return;
        }
    }

    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            //DC.
        }
    }
}
