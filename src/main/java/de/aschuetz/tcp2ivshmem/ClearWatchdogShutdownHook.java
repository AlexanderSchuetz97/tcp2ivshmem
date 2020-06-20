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

import static de.aschuetz.tcp2ivshmem.Constants.ADDRESS_STATE;
import static de.aschuetz.tcp2ivshmem.Constants.ADDRESS_WATCHDOG;

public class ClearWatchdogShutdownHook extends Thread {

    public void run() {
        if (!IvshmemConnectionWatchdog.getInstance().hasChanged()) {
            try {
                Main.memory.write(ADDRESS_STATE, (byte)0);
            } catch (Exception e) {
                //.
            }
            try {
                Main.memory.write(ADDRESS_WATCHDOG, -1);
            } catch (Exception e) {
                //.
            }
        }
        Main.memory.close();
        System.out.println("Goodbye.");
    }
}
