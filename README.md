# tcp2ivshmem
tcp2ivshmem is a java command line application to tunnel tcp connections through qemu ivshmem shared memory without using a network interface to do so.
## License
tcp2ivshmem is released under the GNU General Public License Version 3. <br>A copy of the GNU General Public License Version 3 can be found in the COPYING file.<br>

The file "mvnw" is part of the maven-wrapper project, released under the Apache License Version 2.<br>
See https://github.com/takari/maven-wrapper for more information regarding maven-wrapper.
## Building
If you do not want to build tcp2ivshmem yourself then you may also use the standalone executable jar file inside the 
examples folder or one of the releases provided on github.

Requirements:
* JDK 7 or newer (Oracle or OpenJDK both work fine.)

````
./mvnw clean package
````
The compiled jar file with dependencies should then be located in the target folder.
## Usage
See Readme in examples folder.