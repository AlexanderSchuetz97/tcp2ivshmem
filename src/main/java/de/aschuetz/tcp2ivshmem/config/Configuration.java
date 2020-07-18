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

import de.aschuetz.ivshmem4j.api.SharedMemoryException;
import de.aschuetz.ivshmem4j.windows.IvshmemWindowsDevice;
import de.aschuetz.tcp2ivshmem.Constants;

import java.util.*;

public final class Configuration {

    private OS operatingSystem;

    private Boolean master = null;

    private String dev = null;

    private Long size = null;

    private Boolean linuxIsPlain;

    private final Collection<Forwarding> local = new ArrayList<>();

    private final Collection<Forwarding> remote = new ArrayList<>();

    private Boolean interrupts;

    private Long spinWithInterrupts;

    private Long spinWithoutInterrupts;

    private Integer maxTcpConnections;

    private Configuration() {
        //.
    }


    public static Configuration create(String[] args) throws IllegalArgumentException {
        Configuration config = new Configuration();
        config.operatingSystem = OS.detect();
        if (config.operatingSystem == OS.OTHER) {
            throw new IllegalArgumentException("Operating system is unsupported.");
        }

        config.parseArgs(args);
        config.validateArgs();

        return config;
    }

    private void parseArgs(String[] args) throws IllegalArgumentException {
        for (int i = 0; i < args.length; i++) {
            switch (args[i].trim()) {
                case ("-pl"):
                    //FallThru
                case ("--plain"):
                    if (linuxIsPlain != null) {
                        throw new IllegalArgumentException("Ivshmem type already set " + args[i] + " at " + i + " is trying to set it again.");
                    }
                    linuxIsPlain = true;
                    break;
                case("-db"):
                    //FallThru
                case("--doorbell"):
                    if (linuxIsPlain != null) {
                        throw new IllegalArgumentException("Ivshmem type already set " + args[i] + " at " + i + " is trying to set it again.");
                    }
                    linuxIsPlain = false;
                    break;
                case ("--master"):
                    //FallThru
                case ("-m"):
                    if (master != null) {
                        throw new IllegalArgumentException("Operation mode already set " + args[i] + " at " + i + " is trying to set it again.");
                    }
                    master = true;
                    break;
                case ("-s"):
                    //FallThru
                case ("--slave"):
                    if (master != null) {
                        throw new IllegalArgumentException("Operation mode already set " + args[i] + " at " + i + " is trying to set it again.");
                    }
                    master = false;
                    break;
                case ("-d"):
                    //FallThru
                case ("--dev"):
                    //FallThru
                case ("--device"):
                    if (dev != null) {
                        throw new IllegalArgumentException("Device already set " + args[i] + " at " + i + " is trying to set it again.");
                    }

                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }

                    dev = args[i + 1];
                    i++;
                    break;
                case ("-b"):
                    //FallThru
                case ("--buffer"):
                    if (size != null) {
                        throw new IllegalArgumentException("Buffer size already set " + args[i] + " at " + i + " is trying to set it again.");
                    }

                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }

                    try {
                        size = Long.parseLong(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        throw new IllegalArgumentException("Buffer size is not a valid number " + args[i] + " at " + i + " expected a number greater than " + Constants.MIN_REQUIRED_MEMORY_SIZE + " at " + (i + 1) + " but got " + args[i + 1]);
                    }

                    if (size <= Constants.MIN_REQUIRED_MEMORY_SIZE) {
                        throw new IllegalArgumentException("Buffer is too small " + args[i] + " at " + i + " expected a number greater than " + Constants.MIN_REQUIRED_MEMORY_SIZE + " at " + (i + 1) + " but got " + args[i + 1]);
                    }
                    i++;
                    break;
                case("-ls"):
                    throw new IllegalArgumentException("-ls must be the only argument");
                case("-L"):
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }
                    local.add(parseForwarding(args[i+1]));
                    i++;
                    break;
                case("-R"):
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }
                    remote.add(parseForwarding(args[i+1]));
                    i++;
                    break;
                case("-ni"):
                    if (interrupts != null) {
                        throw new IllegalArgumentException("No interrupt size already set " + args[i] + " at " + i + " is trying to set it again.");
                    }
                    interrupts = false;
                    break;
                case("-sni"):
                    if (spinWithoutInterrupts != null) {
                        throw new IllegalArgumentException("Spin time without interrupts already set " + args[i] + " at " + i + " is trying to set it again.");
                    }

                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }

                    try {
                        spinWithoutInterrupts = Long.parseLong(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        throw new IllegalArgumentException("Spin time without interrupts is not a valid number " + args[i] + " at " + i + " expected a positive number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }

                    if (spinWithoutInterrupts <= 0) {
                        throw new IllegalArgumentException("Spin time without interrupts is too small " + args[i] + " at " + i + " expected a number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }
                    i++;
                    break;
                case("-si"):
                    if (spinWithInterrupts != null) {
                        throw new IllegalArgumentException("Spin time with interrupts already set " + args[i] + " at " + i + " is trying to set it again.");
                    }

                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }

                    try {
                        spinWithInterrupts = Long.parseLong(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        throw new IllegalArgumentException("Spin time with interrupts is not a valid number " + args[i] + " at " + i + " expected a positive number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }

                    if (spinWithInterrupts <= 0) {
                        throw new IllegalArgumentException("Spin time with interrupts is too small " + args[i] + " at " + i + " expected a number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }

                    if (spinWithInterrupts < 500) {
                        System.out.println("You have specified a spin time with interrupts of less than 500ms. Consider not using interrupts as what your trying to do here does not make sense.");
                    }
                    i++;
                    break;
                case("--max-connections"):
                case("-mcon"):
                    if (maxTcpConnections != null) {
                        throw new IllegalArgumentException("Max TCP connection count already set " + args[i] + " at " + i + " is trying to set it again.");
                    }

                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(args[i] + " expected one more argument.");
                    }

                    try {
                        maxTcpConnections = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        throw new IllegalArgumentException("Max TCP connection count is not a valid number " + args[i] + " at " + i + " expected a positive number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }

                    if (maxTcpConnections <= 0) {
                        throw new IllegalArgumentException("Max TCP connection count is too small " + args[i] + " at " + i + " expected a number greater than 0 at " + (i + 1) + " but got " + args[i + 1]);
                    }
                    i++;
                    break;
                default:
                    throw new IllegalArgumentException("Illegal argument " + args[i] + " at " + i);
            }
        }
    }

    private Forwarding parseForwarding(String raw) {
        if (raw.indexOf(":") == raw.lastIndexOf(":") || raw.startsWith(":") || raw.endsWith(":")) {
            throw new IllegalArgumentException("Invalid forwarding line " + raw);
        }

        String port = raw.substring(0, raw.indexOf(":"));
        String address = raw.substring(raw.indexOf(":")+1, raw.lastIndexOf(":"));
        String addressPort = raw.substring(raw.lastIndexOf(":")+1);
        int portInt;
        int addressPortInt;
        try {
            portInt = Integer.parseInt(port);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("Invalid forwarding line " + raw + " first element is not a number.");
        }

        if (portInt > 0xffff) {
            throw new IllegalArgumentException("First port is too high " + raw + "  expected a number between 1 and 65565 but got " + port);
        }

        if (portInt <= 0) {
            throw new IllegalArgumentException("First Port is too low "  + raw + "  expected a number between 1 and 65565 but got " + port);
        }

        try {
            addressPortInt = Integer.parseInt(addressPort);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("Invalid forwarding line " + raw + " last element is not a number.");
        }

        if (addressPortInt > 0xffff) {
            throw new IllegalArgumentException("First port is too high " + raw + "  expected a number between 1 and 65565 but got " + addressPortInt);
        }

        if (addressPortInt <= 0) {
            throw new IllegalArgumentException("First Port is too low "  + raw + "  expected a number between 1 and 65565 but got " + addressPortInt);
        }

        if (address.length() == 0) {
            throw new IllegalArgumentException("Address is too empty " + raw + "  expected a ip address or hostname but got empty string.");
        }

        return new Forwarding(portInt, address, addressPortInt);
    }

    private void validateArgs() throws IllegalArgumentException {
        List<String> errors = new ArrayList<>();



        if (master == null) {
            errors.add("Operation mode is missing. Use -c or -s.");
        }

        if (dev == null) {
            if (operatingSystem == OS.LINUX) {
                errors.add("Device is missing. Use -d.");
            } else {
                try {
                    if (IvshmemWindowsDevice.getSharedMemoryDevices().size() > 1) {
                        errors.add("Device is missing and there is more than 1 PCI Device attached to this vm.");
                    }
                } catch (SharedMemoryException e) {
                    errors.add("Couldn't enumerate ivhshmem pci devices err:" + e.getMessage());
                }
            }
        }

        if (operatingSystem == OS.LINUX && linuxIsPlain == null && dev != null) {
            if (dev.startsWith("/dev/shm") || dev.startsWith("/sys/bus")) {
                System.out.println("Assuming ivshmem-plain explicitly use -db if this is wrong.");
                linuxIsPlain = true;
            } else {
                System.out.println("Assuming ivshmem-doorbell explicitly use -pl if this is wrong.");
                linuxIsPlain = false;
            }
        }

        if (operatingSystem == OS.WINDOWS && linuxIsPlain != null) {
            errors.add("-db/--doorbell and -pl/--plain are not supported on windows as windows only supports pci mode");
        }

        if (operatingSystem == OS.LINUX && linuxIsPlain != null && !linuxIsPlain && size != null) {
            errors.add("Specifying the memory size is only supported when using ivshmem-plain use -pl if you want to force the usage of ivhsmem-plain.");
        }

        if (operatingSystem == OS.WINDOWS && size != null) {
            errors.add("Specifying the memory size is not supported on windows as it is determined by the qemu/ivshmem-server settings.");
        }

        if (Boolean.FALSE.equals(interrupts) && spinWithInterrupts != null) {
            errors.add("Spin time with interrupts cannot be set when not using interrupts.");
        }

        if (operatingSystem == OS.LINUX && linuxIsPlain && spinWithInterrupts != null) {
            errors.add("Spin time with interrupts cannot be set when using ivshmem-plain.");
        }

        if (Boolean.FALSE.equals(master) && maxTcpConnections != null) {
            errors.add("Only the master can set the max tcp connection count.");
        }

        Set<Integer> errPorts = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        for (Forwarding forwarding : local) {
            if (!ports.add(forwarding.getPort())) {
                if (errPorts.add(forwarding.getPort())) {
                    errors.add("Local port " + forwarding.getPort() + " is used multiple times.");
                }
            }
        }

        errPorts.clear();
        ports.clear();
        for (Forwarding forwarding : remote) {
            if (!ports.add(forwarding.getPort())) {
                if (errPorts.add(forwarding.getPort())) {
                    errors.add("Remote port " + forwarding.getPort() + " is used multiple times.");
                }
            }
        }

        if (errors.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (String error : errors) {
            builder.append(error);
            builder.append('\n');
        }

        throw new IllegalArgumentException(builder.toString());
    }

    public OS getOperatingSystem() {
        return operatingSystem;
    }

    public boolean isMaster() {
        return master;
    }

    public String getDevice() {
        return dev;
    }

    public Long getSize() {
        return size;
    }

    public Boolean getLinuxIsPlain() {
        return linuxIsPlain;
    }

    public Collection<Forwarding> getLocal() {
        return local;
    }

    public Collection<Forwarding> getRemote() {
        return remote;
    }

    public Boolean useInterrupts() {
        return interrupts;
    }

    public long getSpinWithInterrupts() {
        return spinWithInterrupts == null ? Constants.DEFAULT_SPIN_DATA_WITH_INTERRUPTS : spinWithInterrupts;
    }

    public Long getSpinWithoutInterrupts() {
        return spinWithoutInterrupts == null ? Constants.DEFAULT_SPIN_DATA_WITHOUT_INTERRUPTS : spinWithoutInterrupts;
    }

    public Integer getMaxTcpConnections() {
        return maxTcpConnections == null ? Constants.DEFAULT_MAX_CONCURRENT_TCP_CONNECTIONS : maxTcpConnections;
    }
}
