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
package io.github.alexanderschuetz97.tcp2ivshmem;


import io.github.alexanderschuetz97.tcp2ivshmem.config.Configuration;
import io.github.alexanderschuetz97.tcp2ivshmem.config.Forwarding;
import io.github.alexanderschuetz97.tcp2ivshmem.config.OS;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.Constants;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.IvhsmemMasterBridge;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.IvshmemBridge;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.IvshmemSlaveBridge;
import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.RingBuffer;
import io.github.alexanderschuetz97.ivshmem4j.api.Ivshmem;
import io.github.alexanderschuetz97.ivshmem4j.api.IvshmemMemory;
import io.github.alexanderschuetz97.ivshmem4j.api.WindowsIvshmemPCIDevice;
import io.github.alexanderschuetz97.nativeutils.api.NativeMemory;
import io.github.alexanderschuetz97.nativeutils.api.NativeUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static Configuration config;

    public static IvshmemMemory shmemory;

    public static NativeMemory memory;

    public static boolean useInterrupts = false;

    public static ExecutorService ex = Executors.newCachedThreadPool();

    public static long clientToServerOffset;

    public static long serverToClientOffset = Constants.MEMORY_OVERHEAD;

    public static long ringBufferSize;

    public static int maxConcurrentTcpConnections;

    public static void printUsageAndExit() {

        String usage = "Ivshmem bridge: \n";
        usage += "-m   \t--master         \tMaster mode   \tEither -m or -s is mandatory never both. A bridge needs a master and a slave.\n";
        usage += "-s   \t--slave          \tSlave mode    \tEither -m or -s is mandatory never both. A bridge needs a master and a slave.\n";
        usage += "-d   \t--dev --device   \tIvshmem device\tThe path or name to the Ivshmem device to use. Optional on windows if there is only 1 device.\n";
        usage += "-ni  \t                 \tNo interrupts \tDisables interrupts on ivshmem-doorbell.\n";
        usage += "-si  \t                 \tSpin time     \tSets the spin time in ms when using interrupts defaults to 1000ms\n";
        usage += "-sni \t                 \tSpin time     \tSets the spin time in ms when not using interrupts defaults to 10ms. This defines the maximum latency. Lower values will increase CPU usage.\n";
        usage += "-mcon\t--max-connections\t              \tMaximum concurrent TCP connection count. Only settable by master. Defaults to 128.\n";
        usage+="\n";
        usage+="Linux specific:\n";
        usage+="-b \t--buffer  \tShared memory size in bytes\tOnly needed for ivshmem-plain. Only required if shared memory file does not yet exist.\n";
        usage+="-db\t--doorbell\tForce ivshmem-doorbell     \tOptional for advanced use only.\n";
        usage+="-pl\t--plain   \tForce ivshmem-plain        \tOptional for advanced use only.\n";
        usage+="\n";
        usage+="Windows specific:\n";
        usage+="-ls\t \tList Ivshmem PCI devices\tMust be the only argument. Utility to command.\n";
        usage+="\n";
        usage+="Forwading:\n";
        usage+="-L\t-L lport:dst:dstport\t Static forwarding from local tcp port lport to address dst tcp port dstport.\n";
        usage+="-R\t-R rport:dst:dstport\t Static forwarding from remote tcp port rport to address dst tcp port dstport.\n";
        usage+="-D\t-D port             \t Start a local Socks5 proxy server on the port. (Only Accepts TCP connections)\n";




        System.out.println(usage);
        System.out.flush();
        System.exit(-1);
    }

    public static void listWindowsDevices() {
        if (OS.detect() != OS.WINDOWS) {
            System.out.println("-ls is only supported on windows.");
            System.exit(-1);
        }

        Collection<WindowsIvshmemPCIDevice> devices = null;
        try {
            devices = Ivshmem.windowsListPCI();
        } catch (Exception e) {
            System.out.println("Couldn't enumerate ivhshmem pci devices");
            e.printStackTrace();
            System.exit(-1);
        }

        System.out.println("Available devices: " + devices.size());
        for (WindowsIvshmemPCIDevice device : devices) {
            System.out.println("Size: " + device.getSize() + " bytes Device: " + device.getName());
        }

        System.out.println();
        System.out.println("You may use either the size of the device or a part of the device path (uses \"contains\") to identify the device");

    }

    public static void createMemory() {
        if (config.getOperatingSystem() == OS.LINUX) {
            if (Boolean.TRUE.equals(config.getLinuxIsPlain())) {
                if (config.getSize() == null) {
                    shmemory = Ivshmem.plain(config.getDevice());
                    return;
                }

                shmemory = Ivshmem.plain(config.getDevice(), config.getSize());
                return;
            }

            shmemory = Ivshmem.doorbell(config.getDevice(), 5000, Main.ex);
            return;
        }

        Collection<WindowsIvshmemPCIDevice> devices = Ivshmem.windowsListPCI();

        for (WindowsIvshmemPCIDevice device : devices) {
            if (config.getDevice() == null)  {
                shmemory = Ivshmem.windowsPCI(device, Main.ex);
                return;
            }

            if (config.getDevice().equals(String.valueOf(device.getSize()))) {
                System.out.println("Will open device " + device.getName());
                shmemory = Ivshmem.windowsPCI(device, Main.ex);

                return;
            }

            if(device.getName() != null && device.getName().contains(config.getDevice())) {
                System.out.println("Will open device " + device.getName());
                shmemory = Ivshmem.windowsPCI(device, Main.ex);
                return;
            }

        }

        System.out.println("Device " + config.getDevice() + " not found.");
        listWindowsDevices();
        System.exit(-1);
    }

    public static void main(String[] args) {
        System.out.println("Tcp2ivshmem is free software released under the GNU General Public License v3.\n" +
                "A copy of the GNU General Public License v3 should be provided in the COPYING file within this executable.\n" +
                "If not see https://www.gnu.org/licenses/\n\n" +
                "By using tcp2ivshmem you agree to the terms of the license agreement.\n\n");

        if (args == null || args.length == 0) {
            printUsageAndExit();
            return;
        }

        if (args.length == 1 && ("help".equalsIgnoreCase(args[0]) || "-help".equalsIgnoreCase(args[0]) || "--help".equalsIgnoreCase(args[0]))) {
            printUsageAndExit();
            return;
        }

        if (!NativeUtils.isLinux() && !NativeUtils.isWindows()) {
            System.out.println("Unsupported OS or CPU architecture.");
            System.exit(-1);
            return;
        }

        if (args.length == 1 && "-ls".equalsIgnoreCase(args[0])) {
            listWindowsDevices();
            System.exit(0);
            return;
        }


        try {
            config = Configuration.create(args);
        } catch (IllegalArgumentException exc) {
            System.out.println(exc.getMessage());
            System.out.println();
            System.out.println();
            printUsageAndExit();
            return;
        }

        if (config.getOperatingSystem() == OS.LINUX) {
            System.out.println("Detected Linux operating system.");
        } else {
            System.out.println("Detected Windows operating system.");
        }

        try {
            createMemory();
            memory = shmemory.getMemory();
        } catch (Exception e) {
            System.out.println("Error creating shared memory.");
            e.printStackTrace();
            System.exit(-1);
        }

        if (memory == null) {
            System.out.println("Error creating shared memory. null.");
            System.exit(-1);
        }

        System.out.println("Using shared memory: " + memory);

        if (memory.size() < Constants.MIN_REQUIRED_MEMORY_SIZE) {
            System.out.println("Shared memory is too small.");
            System.exit(-1);
        }

        long tempRes = memory.size() - Constants.MEMORY_OVERHEAD - (2* RingBuffer.OVERHEAD);
        tempRes -= tempRes % 32;
        ringBufferSize = tempRes / 2;
        if (ringBufferSize <= 0) {
            System.out.println("Shared memory is too small.");
            System.exit(-1);
        }

        clientToServerOffset = serverToClientOffset + RingBuffer.OVERHEAD + ringBufferSize;

        if (!shmemory.supportsInterrupts() && Boolean.TRUE.equals(config.useInterrupts())) {
            System.out.println("Interrupts not supported by shared memory.");
            System.exit(-1);
            return;
        }

        if (shmemory.supportsInterrupts() && !Boolean.FALSE.equals(config.useInterrupts())) {
            useInterrupts = true;
        }

        System.out.println("Using ring buffer size: " + ringBufferSize + ".");


        if (useInterrupts) {
            System.out.println("Own Peer ID " + shmemory.getOwnPeerID());
            System.out.println("Will use interrupts if other side supports them too.");
        } else {
            System.out.println("We do not support interrupts.");
        }

        IvshmemBridge bridge;
        if (config.isMaster()) {
            bridge = new IvhsmemMasterBridge();
        } else {
            bridge = new IvshmemSlaveBridge();
        }

        bridge.start();
        System.out.println();
        System.out.println();


        for (Forwarding forwarding : config.getLocal()) {
            try {
                ServerSocket socket = new ServerSocket(forwarding.getPort());
                bridge.addServer(socket, forwarding.getAddress(), forwarding.getAddressPort());
            } catch (IOException e) {
                System.out.println("Creating TCP server for local forwarding failed. Port: " + forwarding.getPort() + " Err: " + e.getMessage());
                System.exit(-1);
                return;
            }
        }

        for (Integer socks5Port : config.getSocks5proxies()) {
            try {
                ServerSocket socket = new ServerSocket(socks5Port);
                bridge.addSocks5Proxy(socket);
            } catch (IOException e) {
                System.out.println("Creating SOCKS5 proxy server failed. Port: " + socks5Port + " Err: " + e.getMessage());
                System.exit(-1);
                return;
            }
        }

        for (Forwarding forwarding : config.getRemote()) {
            try {
                if (!bridge.openServerOnRemote(null, forwarding.getPort(), forwarding.getAddress(), forwarding.getAddressPort())) {
                    System.out.println("Creating remote TCP server for remote forwarding failed. Port: " + forwarding.getPort());
                    System.exit(-1);
                    return;
                }
            } catch (IOException e) {
                System.out.println("Creating remote TCP server for remote forwarding failed. Port: " + forwarding.getPort() + " Err: " + e.getMessage());
                System.exit(-1);
                return;
            }
        }

    }

}
