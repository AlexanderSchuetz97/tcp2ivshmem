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
package io.github.alexanderschuetz97.tcp2ivshmem.sockets;

import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.IvshmemBridge;
import io.github.alexanderschuetz97.tcp2ivshmem.packets.PacketUtil;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

public class TcpSocketContainer {
    private final IvshmemBridge ivshmemBridge;
    private final int offset;
    private ReentrantLock lock = new ReentrantLock();
    private TcpSocket[] sockets;

    public TcpSocketContainer(IvshmemBridge ivshmemBridge, int offset, int size) {
        this.ivshmemBridge = ivshmemBridge;
        this.offset = offset;
        sockets = new TcpSocket[size];
    }

    public int getOffset() {
        return offset;
    }

    public boolean isID(int id) {
        if (id < offset || id-offset >= getSize()) {
            return false;
        }

        return true;
    }

    public int getSize() {
        return sockets.length;
    }

    public TcpSocket add(int id, Socket tcpSocket) throws IOException {
        if (!isID(id)) {
            tcpSocket.close();
            throw new IOException("Invalid socket id");
        }
        TcpSocket socket;
        TcpSocket newSocket;
        lock.lock();
        try {
            socket = getSocket(id);
            remove(socket);
            newSocket = new TcpSocket(id, this, ivshmemBridge, tcpSocket);
            sockets[id-offset] = newSocket;
        } finally {
            lock.unlock();
        }

        if (socket != null) {
            socket.close();
        }
        return newSocket;
    }

    public void data(int id, byte[] data) throws IOException {
        TcpSocket socket = getSocket(id);

        if (socket == null) {
            ivshmemBridge.sendUrgentPacket(PacketUtil.rst(id));
            return;
        }

        if (socket.canBeRemoved()) {
            ivshmemBridge.sendUrgentPacket(PacketUtil.rst(id));
            remove(socket);
            socket.close();
            return;
        }

        socket.queueData(data);
    }


    public void rst(int id) throws IOException {
        TcpSocket socket = getSocket(id);

        if (socket == null) {
            return;
        }

        remove(socket);

        socket.close();
    }

    public void fin(int id) throws IOException {
        TcpSocket socket = getSocket(id);

        if (socket == null) {
            ivshmemBridge.sendUrgentPacket(PacketUtil.rst(id));
            return;
        }

        socket.signalFin();

        if (!socket.canBeRemoved()) {
            return;
        }

        remove(socket);
    }

    public TcpSocket getSocket(int id) {
        id-=offset;
        if (id < 0 || id > sockets.length) {
            return null;
        }

        return sockets[id];
    }

    public void remove(TcpSocket socket) {
        if (socket == null) {
            return;
        }

        if (sockets[socket.getId()-offset] != socket) {
            return;
        }
        lock.lock();
        try {
            if (sockets[socket.getId()-offset] != socket) {
                return;
            }
            sockets[socket.getId()-offset] = null;
            System.out.println("TCP connection to " + socket.getAddress() + " from " + socket.getLocalPort() + " is closing. Tracking id: " + socket.getId());
        } finally {
            lock.unlock();
        }
    }
}
