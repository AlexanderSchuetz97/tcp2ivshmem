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

public class Packet4Data extends AbstractPacket {

    private int id;

    private byte[] data;

    protected Packet4Data() {
        super(PacketEnum.DATA);
    }

    @Override
    public void read(DataInputStream dataInputStream) throws IOException {
        id = dataInputStream.readInt();
        int len = dataInputStream.readUnsignedShort();
        data = new byte[len];
        dataInputStream.readFully(data);
    }

    @Override
    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(id);
        if (data == null) {
            dataOutputStream.writeShort(0);
        } else {
            int len = Math.min(0xffff, data.length);
            dataOutputStream.writeShort(len);
            dataOutputStream.write(data, 0, len);
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
