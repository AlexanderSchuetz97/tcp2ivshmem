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

public class Packet6OpenServerResult extends AbstractPacket {


    private int id;

    private boolean success;

    protected Packet6OpenServerResult() {
        super(PacketEnum.SERVER_RESULT);
    }

    @Override
    public void read(DataInputStream dataInputStream) throws IOException {
        id = dataInputStream.readInt();
        success = dataInputStream.readBoolean();

    }

    @Override
    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(id);
        dataOutputStream.writeBoolean(success);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
