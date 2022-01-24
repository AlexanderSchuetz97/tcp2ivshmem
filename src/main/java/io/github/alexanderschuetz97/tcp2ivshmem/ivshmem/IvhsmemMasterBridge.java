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

import io.github.alexanderschuetz97.tcp2ivshmem.Main;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class IvhsmemMasterBridge extends IvshmemBridge {

    protected void connectToIvshmem() throws Exception {


        int masterNr = Math.abs(new Random().nextInt());
        System.out.println("Master NR "+ masterNr +" is here.");
        Main.memory.write(Constants.ADDRESS_STATE, Constants.STATE_CONNECTING);


        System.out.println("Waiting for ivshmem connection to slave...");
        while(true) {
            byte state = Main.memory.read(Constants.ADDRESS_STATE);
            if (state == 0 || state == Constants.STATE_DISCONNECTED) {
                System.out.println("External interference detected, is there a another master running? (This must not always be the case, will keep trying.)");
                Main.memory.write(Constants.ADDRESS_STATE, Constants.STATE_CONNECTING);
                continue;
            }

            if (state == Constants.STATE_HANDSHAKE) {
                break;
            }

            if (state == Constants.STATE_CONNECTED || state == Constants.STATE_HANDSHAKE_RESPONSE) {
                System.out.println("There is a another master running, exiting.");
                System.exit(-1);
                return;
            }

            Thread.sleep(Constants.SPIN_CONNECT);
        }

        Main.maxConcurrentTcpConnections = Main.config.getMaxTcpConnections();
        init(0, Main.maxConcurrentTcpConnections);

        Main.memory.write(Constants.ADDRESS_MASTER_INTERRUPTS, Main.useInterrupts ? Constants.USE_INTERRUPTS : Constants.DONT_USE_INTERRUPTS);
        Main.memory.write(Constants.ADDRESS_MAX_TCP_CONNECTIONS, Main.maxConcurrentTcpConnections);
        Main.memory.write(Constants.ADDRESS_WATCHDOG, masterNr);
        IvshmemConnectionWatchdog.getInstance().start(masterNr);

        if (!Main.memory.compareAndSet(Constants.ADDRESS_STATE, Constants.STATE_HANDSHAKE, Constants.STATE_HANDSHAKE_RESPONSE)) {
            System.out.println("Could not respond to handshake. Are multiple masters running?");
            System.exit(-1);
        }

        System.out.println("Handshake response send to slave. Waiting for ACK from slave...");
        if (!Main.memory.spin(Constants.ADDRESS_STATE, Constants.STATE_CONNECTED, Constants.SPIN_CONNECT, Constants.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)) {
            System.out.println("Timeout.");
            System.exit(-1);
        }

        Main.useInterrupts &= Main.memory.read(Constants.ADDRESS_SLAVE_INTERRUPTS) == Constants.USE_INTERRUPTS;
        System.out.println("...Ack received, connecting ring buffers...");

        if (Main.useInterrupts) {
            System.out.println("Will use interrupts for communication.");
        } else {
            System.out.println("Will not use interrupts for communication.");
        }

        Future<InputStream> inputStreamFuture = Main.ex.submit(new ConnectRingBufferForInput(Main.clientToServerOffset));
        Future<OutputStream> outputStreamFuture = Main.ex.submit(new ConnectRingBufferForOutput(Main.serverToClientOffset, Main.useInterrupts));

        fromIvshmem = inputStreamFuture.get(Constants.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);
        toIvshmem = outputStreamFuture.get(Constants.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS);

        System.out.println("...Ring buffers connected. Master is ready for operation.");
        System.out.println("Will accept " + Main.maxConcurrentTcpConnections + " maximum concurrent tcp connections");
    }

}
