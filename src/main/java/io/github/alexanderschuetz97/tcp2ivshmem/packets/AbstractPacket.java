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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public abstract class AbstractPacket {

    protected final PacketEnum packetEnum;

    protected AbstractPacket(PacketEnum packetEnum) {
        this.packetEnum = packetEnum;
    }

    public final byte getPacketID() {
        return packetEnum.getId();
    }

    public final PacketEnum getPacketEnum() {
        return packetEnum;
    }

    public abstract void read(DataInputStream dataInputStream) throws IOException;

    public abstract void write(DataOutputStream dataOutputStream) throws IOException;
}
