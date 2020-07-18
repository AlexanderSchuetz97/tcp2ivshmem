## tcp2ivshmem examples
This folder contains 2 scripts to use tcp2ivshmem to tunnel a ssh connection from a windows guest to a linux host.
The SSH server in this scenario runs on the linux host on tcp port 22.
### Prerequisites
##### Windows (Guest)
1. Install JDK 7 or newer. OpenJDK or OracleJDK both work fine.
2. Install the QEMU Ivshmem Guest Driver provided by:<br>
https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/upstream-virtio/
(Use version 0.1-161 or later)
3. Copy the examples folder to the VM
##### Linux (Host)
1. Install JDK 7 or newer. OpenJDK or OracleJDK both work fine.
2. Make sure a SSH Server runs on port 22. (Default Port for SSH)<br>
   You may also use a different application on a different port if you change the port in both scripts.
3. Setup QEMU with a ivshmem-plain device called "tcp2ivshmem" with 128M in size.<br>

##### QEMU Setup on the host:
If you use QEMU/KVM directly then please read the man page of QEMU/KVM on how to add the ivshmem device.<br>
If you use libvirt then you may run:
````
virsh edit <vmname>
````
A text based editor should appear showing an XML file.<br>
Scroll to the end of the XML file to the end of the "devices" section and add:
````
<shmem name='tcp2ivshmem'>
  <model type='ivshmem-plain'/>
  <size unit='M'>128</size>
  <address type='pci' domain='0x0000' bus='0x00' slot='0x0e' function='0x0'/>
</shmem>
````
If you have already added other PCI devices to your virtual machine then you may have to pick a different slot.<br>
Check that the slot number in this case "0x0e" is unique in your config. If you make a mistake the virsh command
will inform you that you have a duplicated slot number when you exit from the text editor.<br>
You may verify this by running the test exe program provided in the Windows Driver.

I also recommend that you create a script that creates the file "/dev/shm/tcp2ivshmem" before the vm is started
because the permissions qemu uses to create the file will not allow tcp2ivshmem to access the file.<br>
Such a script may look like this:
````
touch /dev/shm/tcp2ivshmem
chown <linuxusername>:kvm /dev/shm/tcp2ivshmem
````
If your system runs AppArmor (Ubuntu does)<br> 
then you will have to ensure that QEMU/KVM has permission to access the file.<br>
You may achieve this by running chmod 777 on "/dev/shm/tcp2ivshmem",<br>
or by editing the AppArmor config in "/etc/apparmor.d/libvirt" to allow access to "/dev/shm".

### Running tcp2ivshmem
1. Start your windows virtual machine.
2. Run the guestToHostSSHHost.sh script on the Host.
3. Run the guestToHostSSHGuest.bat on the Guest.
4. Open a SSH Client (like putty) on the Windows VM and connect to localhost port 8000.

If everything succeeded you should end up with a ssh client that is connected to your linux host.<br>

If you are looking to automate this when starting the system be aware that the order of 2 and 3 does not matter.
You may even run guestToHostSSHHost.sh before the VM is even started 
(You will have to adjust the permissions of the file in this case otherwise 
QEMU wont be able to access the file when the VM starts).
