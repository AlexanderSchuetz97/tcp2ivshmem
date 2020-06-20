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
package de.aschuetz.tcp2ivshmem.packets;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class Packet1Connect extends AbstractPacket {

    private int id;
    private String host;
    private int port;


    protected Packet1Connect() {
        super(PacketEnum.CONNECT);
    }

    @Override
    public void read(DataInputStream dataInputStream) throws IOException {
        id = dataInputStream.readInt();
        if (dataInputStream.readBoolean()) {
            host = dataInputStream.readUTF();
        } else {
            host = null;
        }
        port = dataInputStream.readUnsignedShort();

    }

    @Override
    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(id);
        dataOutputStream.writeBoolean(host != null);
        if (host != null) {
            dataOutputStream.writeUTF(host);
        }
        dataOutputStream.writeShort(port);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
