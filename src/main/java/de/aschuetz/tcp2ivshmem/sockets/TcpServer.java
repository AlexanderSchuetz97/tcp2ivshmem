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
package de.aschuetz.tcp2ivshmem.sockets;

import de.aschuetz.tcp2ivshmem.IvshmemBridge;
import de.aschuetz.tcp2ivshmem.Main;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpServer {

    private final ServerSocket server;

    private final IvshmemBridge ivshmemBridge;

    private final String remoteAddress;

    private final int remotePort;

    public TcpServer(IvshmemBridge ivshmemBridge, ServerSocket tpcSocket, String remoteAddress, int remotePort) {
        this.server = tpcSocket;
        this.ivshmemBridge = ivshmemBridge;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;

    }

    public void start() {
        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        });
    }

    public void accept() {
        while(!server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                ivshmemBridge.addTcpSocket(socket, remoteAddress, remotePort);
            } catch (IOException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                e.printStackTrace();
            }
        }
    }

    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            //DC.
        }
    }
}
