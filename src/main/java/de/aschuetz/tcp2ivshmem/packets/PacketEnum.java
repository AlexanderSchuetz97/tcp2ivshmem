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

public enum PacketEnum {
    CONNECT(1) {
        @Override
        public Packet1Connect create() {
            return new Packet1Connect();
        }
    },
    FIN(2) {
        @Override
        public Packet2Fin create() {
            return new Packet2Fin();
        }
    },
    RST(3){
        @Override
        public Packet3Rst create() {
            return new Packet3Rst();
        }
    },
    DATA(4){
        @Override
        public Packet4Data create() {
            return new Packet4Data();
        }
    },
    SERVER(5){
        @Override
        public Packet5OpenServer create() {
            return new Packet5OpenServer();
        }
    },
    SERVER_RESULT(6) {
        @Override
        public Packet6OpenServerResult create() {
            return new Packet6OpenServerResult();
        }
    };

    final byte id;

    PacketEnum(int id) {
        this.id = (byte) id;
    }

    public byte getId() {
        return id;
    }

    public abstract <T extends AbstractPacket> T create();


    static PacketEnum[] packets = new PacketEnum[16];

    static {
        for (PacketEnum packetEnum: PacketEnum.values()) {
            packets[packetEnum.getId()] = packetEnum;
        }
    }
}
