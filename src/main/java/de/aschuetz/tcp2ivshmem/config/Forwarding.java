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
package de.aschuetz.tcp2ivshmem.config;

public class Forwarding {

    private final int port;

    private final String address;

    private final int addressPort;

    public Forwarding(int port, String address, int addressPort) {
        this.port = port;
        this.address = address;
        this.addressPort = addressPort;
    }

    public int getPort() {
        return port;
    }

    public String getAddress() {
        return address;
    }

    public int getAddressPort() {
        return addressPort;
    }
}
