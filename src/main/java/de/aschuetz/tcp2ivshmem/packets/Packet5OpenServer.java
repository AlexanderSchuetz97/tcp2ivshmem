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

public class Packet5OpenServer extends AbstractPacket {

    private int id;

    private String bindAddress;

    private int bindPort;

    private String destinationAddress;

    private int destinationPort;

    protected Packet5OpenServer() {
        super(PacketEnum.SERVER);
    }

    @Override
    public void read(DataInputStream dataInputStream) throws IOException {
        id = dataInputStream.readInt();
        if (dataInputStream.readBoolean()) {
            bindAddress = dataInputStream.readUTF();
        } else {
            bindAddress = null;
        }
        bindPort = dataInputStream.readUnsignedShort();

        if (dataInputStream.readBoolean()) {
            destinationAddress = dataInputStream.readUTF();
        } else {
            destinationAddress = null;
        }

        destinationPort = dataInputStream.readUnsignedShort();
    }

    @Override
    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(id);
        dataOutputStream.writeBoolean(bindAddress != null);
        if (bindAddress != null) {
            dataOutputStream.writeUTF(bindAddress);
        }

        dataOutputStream.writeShort(bindPort);

        dataOutputStream.writeBoolean(destinationAddress != null);
        if (destinationAddress != null) {
            dataOutputStream.writeUTF(destinationAddress);
        }

        dataOutputStream.writeShort(destinationPort);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getBindPort() {
        return bindPort;
    }

    public void setBindPort(int bindPort) {
        this.bindPort = bindPort;
    }

    public String getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(String destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
    }
}
