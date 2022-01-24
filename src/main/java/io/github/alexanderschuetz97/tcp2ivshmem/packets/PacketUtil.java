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
package io.github.alexanderschuetz97.tcp2ivshmem.packets;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketUtil {

    public static Packet3Rst rst(int id) {
        Packet3Rst rst = PacketEnum.RST.create();
        rst.setId(id);
        return rst;
    }

    public static Packet2Fin fin(int id) {
        Packet2Fin fin = PacketEnum.FIN.create();
        fin.setId(id);
        return fin;
    }

    public static Packet1Connect connect(int id, String host, int port) {
        Packet1Connect con = PacketEnum.CONNECT.create();
        con.setId(id);
        con.setHost(host);
        con.setPort(port);
        return con;
    }

    public static Packet4Data data(int id, byte[] data, int len) {
        byte[] tempBuf = new byte[len];
        System.arraycopy(data, 0, tempBuf, 0, len);
        Packet4Data pdata = PacketEnum.DATA.create();
        pdata.setId(id);
        pdata.setData(tempBuf);
        return pdata;
    }

    public static AbstractPacket readPacket(DataInputStream dataInputStream) throws IOException {
        int pid = dataInputStream.read();

        if (pid < 0 || pid > PacketEnum.packets.length || PacketEnum.packets[pid] == null) {
            throw new IOException("Illegal Packet " + pid);
        }

        AbstractPacket packet = PacketEnum.packets[pid].create();
        packet.read(dataInputStream);
        return packet;
    }

    public static void writePacket(AbstractPacket packet, DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.write(packet.getPacketID());
        packet.write(dataOutputStream);
        dataOutputStream.flush();
    }

    private static final AtomicInteger SERVER_ID_COUNTER = new AtomicInteger(0);

    public static Packet5OpenServer server(String bindAddress, int bindPort, String destinationAddress, int destinationPort) {
        Packet5OpenServer srv = PacketEnum.SERVER.create();
        srv.setId(SERVER_ID_COUNTER.getAndIncrement());
        srv.setBindAddress(bindAddress);
        srv.setBindPort(bindPort);
        srv.setDestinationAddress(destinationAddress);
        srv.setDestinationPort(destinationPort);
        return srv;
    }

    public static Packet6OpenServerResult serverResult(int id, boolean success) {
        Packet6OpenServerResult res = PacketEnum.SERVER_RESULT.create();
        res.setId(id);
        res.setSuccess(success);
        return res;
    }
}
