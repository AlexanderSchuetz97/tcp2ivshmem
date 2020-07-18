# tcp2ivshmem
tcp2ivshmem is a java program to tunnel tcp connections through qemu ivshmem shared memory without using a network interface to do so.
## License
tcp2ivshmem is released under the GNU General Public License Version 3. <br>A copy of the GNU General Public License Version 3 can be found in the COPYING file.<br>

The file "mvnw" is part of the maven-wrapper project, released under the Apache License Version 2.<br>
See https://github.com/takari/maven-wrapper for more information regarding maven-wrapper.
## Building
If you do not want to build tcp2ivshmem yourself then you may also use the standalone exetuable jar file inside the examples folder.

Requirements:
* JDK 7 or newer (Oracle or OpenJDK both work fine.)

Install Ivshmem4j 1.0 to your local maven repository (see https://github.com/AlexanderSchuetz97/Ivshmem4j)<br>
Once Ivshmem4j is installed to your local maven reposity run:
````
./mvnw clean package
````
The compiled jar file with dependencies should then be located in the target folder.
## Usage
See Readme in examples folder.