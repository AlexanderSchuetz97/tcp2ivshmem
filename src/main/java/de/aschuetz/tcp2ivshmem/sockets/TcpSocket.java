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
import de.aschuetz.tcp2ivshmem.packets.PacketUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static de.aschuetz.tcp2ivshmem.Constants.*;

public class TcpSocket {

    private final int id;
    private final TcpSocketContainer container;
    private final IvshmemBridge ivshmemBridge;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private volatile boolean running = true;
    private boolean isFin;
    private BlockingQueue<byte[]> toTcpQueue = new ArrayBlockingQueue<>(PACKET_QUEUE_SIZE);


    public TcpSocket(int id, TcpSocketContainer container, IvshmemBridge ivshmemBridge, Socket socket) throws IOException {
        this.id = id;
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.container = container;
        this.ivshmemBridge = ivshmemBridge;
    }

    public int getId() {
        return id;
    }

    public String getAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }
    public int getLocalPort() {
        return socket.getLocalPort();
    }


    public void start() {
        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setName("TCP Socket reader " + id);
                read();
            }
        });

        Main.ex.submit(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setName("TCP Socket writer " + id);
                write();
            }
        });
    }

    public synchronized boolean canBeRemoved() {
        if (!running) {
            return true;
        }

        if (socket.isClosed()) {
            return true;
        }

        if (isFin && socket.isInputShutdown()) {
            return true;
        }

        return false;
    }

    public synchronized void signalFin() {
        if (isFin) {
            return;
        }

        if (!running) {
            return;
        }

        isFin = true;
        try {
            toTcpQueue.put(new byte[0]);
        } catch (InterruptedException e) {
            //DC.
        }
    }

    public synchronized void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            //DC.
        }

        toTcpQueue.clear();

        container.remove(this);
    }

    private synchronized void closeWithRst() {
        if (running) {
            try {
                ivshmemBridge.sendUrgentPacket(PacketUtil.rst(id));
            } catch (IOException e1) {
                //DC.
            }
            close();
        }
    }


    public void queueData(byte[] data) throws IOException {
        if (isFin || !running) {
            return;
        }

        try {
            toTcpQueue.put(data);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private void read() {
        byte[] buf = new byte[SOCKET_BUFFER_SIZE];

        while(running) {
            try {
                int len = input.read(buf);
                if (len == -1) {

                    synchronized (this) {
                        if (running) {
                            ivshmemBridge.sendUrgentPacket(PacketUtil.fin(id));
                            if (socket.isOutputShutdown()) {
                                close();
                                return;
                            } else {
                                socket.shutdownInput();
                                return;
                            }
                        }
                    }
                }

                if (len == 0) {
                    continue;
                }

                ivshmemBridge.sendPacket(PacketUtil.data(id, buf, len));
            } catch (Exception e) {
                closeWithRst();
                return;
            }
        }
    }

    private void write() {
        while(running) {

            byte[] data;
            try {
                data = toTcpQueue.poll(SPIN_SOCKET_QUEUE, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                closeWithRst();
                return;
            }
            if (data == null) {
                continue;
            }

            if (data.length == 0) {
                doFin();
            }


            try {
                output.write(data);
            } catch (IOException e) {
                closeWithRst();
            }

        }

    }

    private synchronized void doFin() {
        if (!running) {
            return;
        }

        if (socket.isInputShutdown()) {
            close();
            return;
        }

        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            closeWithRst();
        }

    }


}
