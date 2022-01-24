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

public class IvshmemConnectionWatchdog {

    public volatile boolean changed;

    public int masterNr = -1;



    private IvshmemConnectionWatchdog() {

    }

    private static final IvshmemConnectionWatchdog INSTANCE = new IvshmemConnectionWatchdog();

    public static IvshmemConnectionWatchdog getInstance() {
        return INSTANCE;
    }

    public synchronized void start(int masterNr) {
        if (this.masterNr != -1) {
            throw new IllegalStateException("Watchdog already started.");
        }

        this.masterNr = masterNr;
        Runtime.getRuntime().addShutdownHook(new ClearWatchdogShutdownHook());
        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                IvshmemConnectionWatchdog.this.run();
            }
        });
    }

    public boolean hasChanged() {
        return changed;
    }

    private void run() {
        Thread.currentThread().setName("IvshmemConnectionWatchdog Thread");
        try {

            while (Main.memory.readInt(Constants.ADDRESS_WATCHDOG) == masterNr) {
                Thread.sleep(Constants.SPIN_WATCHDOG);
            }

            if (Main.config.isMaster()) {
                System.out.println("Slave is gone.");
            } else {
                System.out.println("Master is gone.");
            }
            changed = true;
        } catch (NullPointerException e) {
            return;
        } catch (Exception e) {
            System.out.println("Watchdog error: " + e.getMessage());
        } finally {
            System.exit(0);
        }
    }
}
