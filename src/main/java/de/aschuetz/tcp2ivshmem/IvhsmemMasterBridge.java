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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static de.aschuetz.tcp2ivshmem.Constants.*;
import static de.aschuetz.tcp2ivshmem.Constants.TIMEOUT_CONNECT;

public class IvhsmemMasterBridge extends IvshmemBridge {


    public IvhsmemMasterBridge() {
        super(0, MAX_CONCURRENT_TCP_CONNECTIONS);
    }

    protected void connectToIvshmem() throws Exception {


        int masterNr = Math.abs(new Random().nextInt());
        System.out.println("Master NR "+ masterNr +" is here.");
        Main.memory.write(ADDRESS_STATE, STATE_CONNECTING);


        System.out.println("Waiting for ivshmem connection to slave...");
        while(true) {
            byte state = Main.memory.read(ADDRESS_STATE);
            if (state == 0 || state == STATE_DISCONNECTED) {
                System.out.println("External interference detected, is there a another master running? (This must not always be the case, will keep trying.)");
                Main.memory.write(ADDRESS_STATE, STATE_CONNECTING);
                continue;
            }

            if (state == STATE_HANDSHAKE) {
                break;
            }

            if (state == STATE_CONNECTED || state == STATE_HANDSHAKE_RESPONSE) {
                System.out.println("There is a another master running, exiting.");
                System.exit(-1);
                return;
            }

            Thread.sleep(SPIN_CONNECT);
        }

        Main.memory.write(ADDRESS_MASTER_INTERRUPTS, Main.useInterrupts ? USE_INTERRUPTS : DONT_USE_INTERRUPTS);
        Main.memory.write(ADDRESS_WATCHDOG, masterNr);
        IvshmemConnectionWatchdog.getInstance().start(masterNr);

        if (!Main.memory.compareAndSet(ADDRESS_STATE, STATE_HANDSHAKE, STATE_HANDSHAKE_RESPONSE)) {
            System.out.println("Could not respond to handshake. Are multiple masters running?");
            System.exit(-1);
        }

        System.out.println("Handshake response send to slave. Waiting for ACK from slave...");
        if (!Main.memory.spin(ADDRESS_STATE, STATE_CONNECTED, SPIN_CONNECT, TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)) {
            System.out.println("Timeout.");
            System.exit(-1);
        }

        Main.useInterrupts &= Main.memory.read(ADDRESS_SLAVE_INTERRUPTS) == USE_INTERRUPTS;
        System.out.println("...Ack received, connecting ring buffers...");

        if (Main.useInterrupts) {
            System.out.println("Will use interrupts for communication.");
        } else {
            System.out.println("Will not use interrupts for communication.");
        }

        Future<InputStream> inputStreamFuture = Main.ex.submit(new ConnectRingBufferForInput(Main.clientToServerOffset));
        Future<OutputStream> outputStreamFuture = Main.ex.submit(new ConnectRingBufferForOutput(Main.serverToClientOffset, Main.useInterrupts));

        fromIvshmem = inputStreamFuture.get(TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);
        toIvshmem = outputStreamFuture.get(TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);

        System.out.println("...Ring buffers connected. Master is ready for operation.");
    }

}
