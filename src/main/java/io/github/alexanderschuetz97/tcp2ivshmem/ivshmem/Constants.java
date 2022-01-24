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


public class Constants {
    //Timeouts
    public static final int TIMEOUT_CONNECT = 5000;

    //Spintimes
    public static final int SPIN_CONNECT = 1000;
    public static final int SPIN_WATCHDOG = 1000;

    public static final int DEFAULT_SPIN_DATA_WITHOUT_INTERRUPTS = 10;
    public static final int DEFAULT_SPIN_DATA_WITH_INTERRUPTS = 1000;
    public static final int SPIN_SOCKET_QUEUE = 10000;

    //Addresses
    public static final long ADDRESS_STATE = 0;
    public static final long ADDRESS_MASTER_INTERRUPTS = ADDRESS_STATE+1;
    public static final long ADDRESS_SLAVE_INTERRUPTS = ADDRESS_MASTER_INTERRUPTS+1;
    public static final long ADDRESS_WATCHDOG = ADDRESS_STATE + 4;
    public static final long ADDRESS_MAX_TCP_CONNECTIONS = ADDRESS_WATCHDOG +4;

    //STATE_VALUES
    public static final byte STATE_CONNECTING = 1;
    public static final byte STATE_HANDSHAKE = 2;
    public static final byte STATE_HANDSHAKE_RESPONSE = 3;
    public static final byte STATE_CONNECTED = 4;
    public static final byte STATE_DISCONNECTED = 5;

    public static final byte USE_INTERRUPTS = 1;
    public static final byte DONT_USE_INTERRUPTS = 0;




    //Misc
    public static final int SOCKET_BUFFER_SIZE = 8096;
    public static final int PACKET_QUEUE_SIZE = 128;

    public static final int DEFAULT_MAX_CONCURRENT_TCP_CONNECTIONS = 128;
    public static final int MEMORY_OVERHEAD = 64;
    public static final int MIN_REQUIRED_MEMORY_SIZE = (2* RingBuffer.OVERHEAD) + MEMORY_OVERHEAD + 64;

}
