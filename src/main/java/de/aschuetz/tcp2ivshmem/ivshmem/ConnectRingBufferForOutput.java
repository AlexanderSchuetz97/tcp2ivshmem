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
package de.aschuetz.tcp2ivshmem.ivshmem;

import de.aschuetz.ivshmem4j.util.RingBuffer;
import de.aschuetz.tcp2ivshmem.Main;

import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static de.aschuetz.tcp2ivshmem.ivshmem.Constants.*;

public class ConnectRingBufferForOutput implements Callable<OutputStream> {
    private final long address;

    private final boolean interrupts;

    public ConnectRingBufferForOutput(long address, boolean interrupts) {
        this.address = address;
        this.interrupts = interrupts;
    }

    @Override
    public OutputStream call() throws Exception {
        System.out.println("Connecting shared memory ring buffer for output at address " + address +".");
        RingBuffer tempBuf = new RingBuffer(Main.memory, address , Main.config.getSpinWithoutInterrupts(), Main.config.getSpinWithInterrupts());
        tempBuf.cleanMemoryArea();
        try {
            if (interrupts) {
                return tempBuf.connectOutputStream(0, Main.ringBufferSize, TIMEOUT_CONNECT, SPIN_CONNECT, TimeUnit.MILLISECONDS);
            }
            return tempBuf.connectOutputStream(Main.ringBufferSize, TIMEOUT_CONNECT, SPIN_CONNECT, TimeUnit.MILLISECONDS);
        } finally {
            if (interrupts && !tempBuf.usesInterrupts()) {
                System.out.println("Using interrupts for communication failed! Falling back to spinning.");
            }

            if (tempBuf.usesInterrupts()) {
                System.out.println("Interrupts will be sent to peer " + tempBuf.getOtherPeer());
            }
        }
    }
}
