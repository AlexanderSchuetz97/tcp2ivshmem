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

import java.io.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static de.aschuetz.tcp2ivshmem.Constants.*;

public class IvshmemSlaveBridge extends IvshmemBridge {

    @Override
    protected void connectToIvshmem() throws Exception {
        System.out.println("Waiting for ivshmem connection from master...");
        Main.memory.spin(ADDRESS_STATE, STATE_CONNECTING, SPIN_CONNECT, -1, TimeUnit.MILLISECONDS);
        System.out.println("...Master is present sending handshake.");

        if (!Main.memory.compareAndSet(ADDRESS_STATE, STATE_CONNECTING, STATE_HANDSHAKE)) {
            System.out.println("Error starting handshake. Are multiple slaves running?");
            System.exit(-1);
            return;
        }

        System.out.println("Handshake sent, waiting for response...");
        if (!Main.memory.spin(ADDRESS_STATE, STATE_HANDSHAKE_RESPONSE, SPIN_CONNECT, TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)) {
            System.out.println("...Timeout.");
            Main.memory.write(ADDRESS_WATCHDOG, -1);
            Main.memory.write(ADDRESS_STATE, STATE_DISCONNECTED);
            System.exit(-1);
            return;
        }

        Main.useInterrupts &= Main.memory.read(ADDRESS_MASTER_INTERRUPTS) == USE_INTERRUPTS;
        Main.maxConcurrentTcpConnections = Main.memory.readInt(ADDRESS_MAX_TCP_CONNECTIONS);
        if (Main.maxConcurrentTcpConnections < 1) {
            System.out.println("Max tcp connection count sent by master is " + Main.maxConcurrentTcpConnections + " this value is invalid shutting down.");
            System.exit(-1);
            return;
        }
        init(Main.maxConcurrentTcpConnections, 0);

        int watchdog = Main.memory.readInt(ADDRESS_WATCHDOG);
        Main.memory.write(ADDRESS_SLAVE_INTERRUPTS, Main.useInterrupts ? USE_INTERRUPTS : DONT_USE_INTERRUPTS);

        System.out.println("...Response received connected to master NR " + watchdog + ". Sending ACK to master.");

        if (!Main.memory.compareAndSet(ADDRESS_STATE, STATE_HANDSHAKE_RESPONSE, STATE_CONNECTED)) {
            System.out.println("Sending ACK failed. Are multiple slaves running?");
            System.exit(-1);
            return;
        }

        System.out.println("ACK sent. Connecting ring buffers...");

        if (Main.useInterrupts) {
            System.out.println("Will use interrupts for communication.");
        } else {
            System.out.println("Will not use interrupts for communication.");
        }

        IvshmemConnectionWatchdog.getInstance().start(watchdog);

        Future<InputStream> inputStreamFuture = Main.ex.submit(new ConnectRingBufferForInput(Main.serverToClientOffset));
        Future<OutputStream> outputStreamFuture = Main.ex.submit(new ConnectRingBufferForOutput(Main.clientToServerOffset, Main.useInterrupts));

        fromIvshmem = inputStreamFuture.get(TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);
        toIvshmem = outputStreamFuture.get(TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);
        System.out.println("...Ring buffers connected. Slave is ready for operation.");
        System.out.println("Will accept " + Main.maxConcurrentTcpConnections + " maximum concurrent tcp connections");
    }


}
